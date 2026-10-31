#requires -Version 7.0
<#
.SYNOPSIS
Build and test locally; optionally detach and notify the current Codex chat once.
.EXAMPLE
pwsh -File scripts/build-local.ps1 -Background
.EXAMPLE
pwsh -File scripts/build-local.ps1 -Targets android -Sign -NotifyTelegram
.EXAMPLE
pwsh -File scripts/build-local.ps1 -InitConfig
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
    [string]$Config,
    [switch]$NoConfig,
    [switch]$InitConfig,
    [int]$CommandTimeout,
    [string]$OutputDirectory,
    [string]$NotifyThread
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path $PSScriptRoot -Parent
$python = (Get-Command python -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$arguments = [Collections.Generic.List[string]]::new()
$arguments.Add((Join-Path $PSScriptRoot 'build-local.py'))
if ($Config) {
    $arguments.Add('--config')
    $arguments.Add([IO.Path]::GetFullPath($Config))
}
if ($NoConfig) { $arguments.Add('--no-config') }
if ($InitConfig) { $arguments.Add('--init-config') }
if ($PSBoundParameters.ContainsKey('CommandTimeout')) {
    $arguments.Add('--command-timeout')
    $arguments.Add([string]$CommandTimeout)
}
if ($Targets) {
    $arguments.Add('--targets')
    $arguments.AddRange($Targets)
}
foreach ($option in @(@('SkipTests', 'skip-tests'), @('Sign', 'sign'),
                     @('NotifyTelegram', 'notify-telegram'))) {
    if ($PSBoundParameters.ContainsKey($option[0])) {
        $prefix = if ($PSBoundParameters[$option[0]]) { '--' } else { '--no-' }
        $arguments.Add($prefix + $option[1])
    }
}
if ($DryRun) { $arguments.Add('--dry-run') }
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
if ($Background -and !$DryRun -and !$InitConfig) {
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
    Write-Host ''
    Write-Host '  UniTrading  /  后台构建' -ForegroundColor Cyan
    Write-Host ('  ' + '-' * 62)
    Write-Host "  启动时间  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')"
    Write-Host "  进程编号  $($process.Id)"
    Write-Host "  进度日志  $OutputDirectory.console.log"
    Write-Host "  完整日志  $(Join-Path $OutputDirectory 'build.log')"
    Write-Host "  结果目录  $OutputDirectory"
    if ($NotifyThread) {
        Write-Host '  完成通知  结束后回调当前聊天一次' -ForegroundColor Green
    } else {
        Write-Host '  完成通知  未指定聊天，结果保存到本地' -ForegroundColor Yellow
    }
    Write-Host ''
} else {
    & $python @arguments
    exit $LASTEXITCODE
}
