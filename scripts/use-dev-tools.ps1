# Dot-source this file to change only the current PowerShell session.
[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$NodeHome
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$nodeVersion = (Get-Content -LiteralPath (Join-Path $projectRoot '.nvmrc') -Raw).Trim()

if (-not $JavaHome) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
        $JavaHome = $env:JAVA_HOME
    } else {
        $jdkRoot = Join-Path $env:ProgramFiles 'Eclipse Adoptium'
        $candidate = Get-ChildItem -LiteralPath $jdkRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -Like 'jdk-21*' | Sort-Object Name -Descending | Select-Object -First 1
        if ($candidate) { $JavaHome = $candidate.FullName }
    }
}
if (-not $NodeHome) {
    $nvmRoot = $env:NVM_HOME
    if (-not $nvmRoot) { $nvmRoot = Join-Path $env:LOCALAPPDATA 'nvm' }
    $NodeHome = Join-Path $nvmRoot "v$nodeVersion"
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'JDK missing. Install Temurin 21 or supply -JavaHome.'
}
if (-not (Test-Path (Join-Path $NodeHome 'node.exe'))) {
    throw "Node $nodeVersion missing. Install it with nvm or supply -NodeHome."
}
$env:JAVA_HOME = $JavaHome
$toolPaths = @((Join-Path $JavaHome 'bin'), $NodeHome)
$dockerBin = Join-Path $env:ProgramFiles 'Docker\Docker\resources\bin'
if (Test-Path -LiteralPath $dockerBin) { $toolPaths += $dockerBin }
$env:Path = ($toolPaths -join ';') + ';' + $env:Path
Write-Host "Selected Java from $JavaHome and Node from $NodeHome for this terminal."
Write-Host 'Run scripts/check-prerequisites.ps1 to validate the selected tools.'
