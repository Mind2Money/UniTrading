#requires -Version 7.0
<#
.SYNOPSIS
Build and test locally; optionally detach and notify the current Codex chat once.
.EXAMPLE
pwsh -File scripts/build-local.ps1 -Background
.EXAMPLE
pwsh -File scripts/build-local.ps1 -Targets android -Sign -NotifyTelegram
#>
[CmdletBinding()]
param(
    [ValidateSet('desktop', 'android', 'ios')]
    [string[]]$Targets,
    [switch]$SkipTests,
    [switch]$Sign,
    [switch]$NotifyTelegram,
    [switch]$DryRun,
    [switch]$Background,
    [string]$OutputDirectory,
    [string]$NotifyThread
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path $PSScriptRoot -Parent
$python = (Get-Command python -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$arguments = [Collections.Generic.List[string]]::new()
$arguments.Add((Join-Path $PSScriptRoot 'build-local.py'))
if ($Targets) {
    $arguments.Add('--targets')
    $arguments.AddRange($Targets)
}
foreach ($option in @(@($SkipTests, '--skip-tests'), @($Sign, '--sign'),
                     @($NotifyTelegram, '--notify-telegram'), @($DryRun, '--dry-run'))) {
    if ($option[0]) { $arguments.Add($option[1]) }
}
if (!$OutputDirectory) {
    $OutputDirectory = Join-Path $repo ('artifacts/local/' + (Get-Date -Format 'yyyyMMdd-HHmmss-ffffff'))
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$arguments.Add('--output')
$arguments.Add($OutputDirectory)
if ($Background -and !$NotifyThread) { $NotifyThread = $env:CODEX_THREAD_ID }
if ($NotifyThread) {
    $arguments.Add('--notify-thread')
    $arguments.Add($NotifyThread)
}
if ($Background -and !$DryRun) {
    $arguments.Add('--console-log')
    $arguments.Add($OutputDirectory + '.console.log')
    # ArgumentList preserves spaces/quotes without constructing shell code. This
    # process waits for its child internally; the model does not poll the build.
    $info = [Diagnostics.ProcessStartInfo]::new($python)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $info.WorkingDirectory = $repo
    foreach ($argument in $arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($info)
    Write-Host "Started PID $($process.Id). Results: $OutputDirectory"
    if (!$NotifyThread) { Write-Host 'No Codex chat ID supplied; results will be saved without a callback.' }
} else {
    & $python @arguments
    exit $LASTEXITCODE
}
