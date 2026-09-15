[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$isWindowsHost = [System.IO.Path]::DirectorySeparatorChar -eq '\'
$maven = if ($isWindowsHost) { Join-Path $projectRoot 'backend/mvnw.cmd' } else { Join-Path $projectRoot 'backend/mvnw' }
$npm = if ($isWindowsHost) { 'npm.cmd' } else { 'npm' }

function Invoke-RequiredCheck {
    param([string]$Label, [string]$Command, [string[]]$CommandArguments)
    $null = Get-Command $Command -ErrorAction Stop
    Write-Host "Running $Label"
    # Native stderr is not itself failure (java and Maven can write warnings there).
    # The native exit code determines success.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command @CommandArguments
        $commandExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($null -eq $commandExit -or $commandExit -ne 0) {
        throw "$Label failed (exit $commandExit)."
    }
}

try {
    Push-Location $projectRoot
    try {
        Invoke-RequiredCheck 'backend verification' $maven @('-f', 'backend/pom.xml', '--batch-mode', '--no-transfer-progress', 'verify')
        Push-Location (Join-Path $projectRoot 'frontend')
        try {
            foreach ($check in @('lint', 'typecheck', 'test:run', 'build')) {
                Invoke-RequiredCheck "frontend $check" $npm @('run', $check)
            }
        } finally { Pop-Location }
    } finally { Pop-Location }
    Write-Host 'All backend and frontend checks passed.'
    exit 0
} catch {
    Write-Error $_ -ErrorAction Continue
    exit 1
}
