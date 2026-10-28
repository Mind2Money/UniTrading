param(
    [string]$OutputDir = (Join-Path $PSScriptRoot 'build/1.1.1'),
    [switch]$SkipSelfTest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$version = '1.0.4314.35'
$expectedHash = '90778deb1b77e36292828b4b5833c9255c27533f40e0ddd55891ae718944edc4'
$packageName = "microsoft.web.webview2.$version.nupkg"
$source = Join-Path $PSScriptRoot 'TradovateLogin.cs'
$output = [IO.Path]::GetFullPath($OutputDir)
if ($output.TrimEnd('\') -eq $PSScriptRoot.TrimEnd('\')) {
    throw 'OutputDir must not be the source directory.'
}
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (!(Test-Path -LiteralPath $compiler -PathType Leaf)) {
    throw 'The Windows .NET Framework x64 C# compiler is required.'
}
[IO.Directory]::CreateDirectory($output) | Out-Null

# Each downloaded archive and its selected SDK files have a fresh isolated directory.
# This script and the application source never execute from that directory.
$workId = [Guid]::NewGuid().ToString('N')
$sdkDir = Join-Path $output "sdk\$workId"
$compileDir = Join-Path $output "compile\$workId"
[IO.Directory]::CreateDirectory($sdkDir) | Out-Null
[IO.Directory]::CreateDirectory($compileDir) | Out-Null
$cache = Join-Path $env:USERPROFILE ".nuget\packages\microsoft.web.webview2\$version\$packageName"
$archive = $cache
if (!(Test-Path -LiteralPath $archive -PathType Leaf)) {
    $downloadDir = Join-Path $output "downloads\$workId"
    [IO.Directory]::CreateDirectory($downloadDir) | Out-Null
    $archive = Join-Path $downloadDir $packageName
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -UseBasicParsing -Uri "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$version/$packageName" -OutFile $archive
}
$hashStream = [IO.File]::OpenRead($archive)
$hashAlgorithm = [Security.Cryptography.SHA256]::Create()
try {
    $hash = ([BitConverter]::ToString($hashAlgorithm.ComputeHash($hashStream))).Replace('-', '').ToLowerInvariant()
} finally {
    $hashAlgorithm.Dispose()
    $hashStream.Dispose()
}
if ($hash -ne $expectedHash) {
    throw 'WebView2 package checksum mismatch. No package contents were loaded.'
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$selected = @{
    'lib/net462/Microsoft.Web.WebView2.Core.dll' = 'Microsoft.Web.WebView2.Core.dll'
    'lib/net462/Microsoft.Web.WebView2.WinForms.dll' = 'Microsoft.Web.WebView2.WinForms.dll'
    'runtimes/win-x64/native/WebView2Loader.dll' = 'WebView2Loader.dll'
    'LICENSE.txt' = 'WebView2.LICENSE.txt'
    'NOTICE.txt' = 'WebView2.NOTICE.txt'
}
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    foreach ($entryName in $selected.Keys) {
        $entry = $zip.GetEntry($entryName)
        if ($null -eq $entry -or $entry.Length -gt 32MB) {
            throw 'Pinned WebView2 package is missing a required SDK file or exceeds its size limit.'
        }
        # Names are fixed above, not derived from untrusted archive paths.
        $destination = Join-Path $sdkDir $selected[$entryName]
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $false)
    }
} finally { $zip.Dispose() }

$framework = Split-Path -Parent $compiler
$exeName = 'UniTrading.TradovateLogin.exe'
$temporaryExe = Join-Path $compileDir $exeName
$arguments = @(
    '/nologo', '/target:winexe', '/platform:x64', '/optimize+', '/codepage:65001', '/utf8output',
    "/out:$temporaryExe",
    "/reference:$(Join-Path $framework 'System.dll')",
    "/reference:$(Join-Path $framework 'System.Core.dll')",
    "/reference:$(Join-Path $framework 'System.Drawing.dll')",
    "/reference:$(Join-Path $framework 'System.Windows.Forms.dll')",
    "/reference:$(Join-Path $framework 'System.Web.Extensions.dll')",
    "/reference:$(Join-Path $sdkDir 'Microsoft.Web.WebView2.Core.dll')",
    "/reference:$(Join-Path $sdkDir 'Microsoft.Web.WebView2.WinForms.dll')",
    $source
)
& $compiler @arguments
if ($LASTEXITCODE -ne 0) { throw 'Native login host compilation failed.' }

foreach ($filename in $selected.Values) {
    Copy-Item -LiteralPath (Join-Path $sdkDir $filename) -Destination (Join-Path $compileDir $filename)
}
if (!$SkipSelfTest) {
    # Redirected anonymous pipes are the exact channel used by the Kotlin launcher.
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $temporaryExe
    $start.Arguments = '--self-test'
    $start.WorkingDirectory = $PSScriptRoot
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(15000)) {
            $process.Kill()
            throw 'Native login host self-test timed out.'
        }
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $null = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0 -or $stdout -notmatch '^SELF_TEST PASS [0-9]+\s*$') {
            # The self-test uses only fake credentials. Still do not propagate arbitrary output.
            throw 'Native login host self-test failed.'
        }
        Write-Output $stdout.Trim()
    } finally { $process.Dispose() }
}

foreach ($filename in @($exeName) + @($selected.Values)) {
    Copy-Item -LiteralPath (Join-Path $compileDir $filename) -Destination (Join-Path $output $filename) -Force
}
Write-Output "Native login host built: $(Join-Path $output $exeName)"
