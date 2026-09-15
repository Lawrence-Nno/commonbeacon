[CmdletBinding()]
param([switch]$RunContainer)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$requiredNode = (Get-Content -LiteralPath (Join-Path $projectRoot '.nvmrc') -Raw).Trim()
$script:failures = 0

function Check-Tool {
    param([string]$Name, [string]$Command, [string[]]$Arguments, [string]$Pattern)
    if (-not (Get-Command $Command -ErrorAction SilentlyContinue)) {
        Write-Host "FAIL $Name is not on PATH."
        $script:failures++
        return $false
    }
    # Windows PowerShell treats native stderr as ErrorRecord objects. Capture it
    # without turning normal java -version output into a terminating error.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Command @Arguments 2>&1
        $commandExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $message = ($output | ForEach-Object { "$_" }) -join [Environment]::NewLine
    if ($commandExit -ne 0 -or ($Pattern -and $message -notmatch $Pattern)) {
        Write-Host "FAIL $Name (exit $commandExit): $message"
        $script:failures++
        return $false
    }
    Write-Host "PASS $Name : $message"
    return $true
}

$null = Check-Tool 'Java 21 runtime' 'java' @('-version') 'version "21\.'
$null = Check-Tool 'Java 21 compiler' 'javac' @('-version') 'javac 21\.'
$null = Check-Tool "Node $requiredNode" 'node' @('--version') ('^v' + [regex]::Escape($requiredNode) + '$')
$null = Check-Tool 'npm 11.8.0' 'npm.cmd' @('--version') '^11\.8\.0$'
$null = Check-Tool 'Git' 'git' @('--version') '^git version '
$null = Check-Tool 'Docker CLI' 'docker' @('--version') 'Docker version'
$null = Check-Tool 'Docker Compose' 'docker' @('compose', 'version') 'Docker Compose version'
$engineReady = Check-Tool 'Docker Linux engine' 'docker' @('info', '--format', '{{.OSType}}') '^linux$'

if ($RunContainer -and $engineReady) {
    $null = Check-Tool 'Disposable container' 'docker' @('run', '--rm', 'hello-world:latest') 'Hello from Docker!'
} elseif ($RunContainer) {
    Write-Host 'Container check skipped because the Linux engine is unavailable.'
}

if ($script:failures -gt 0) {
    Write-Host "$script:failures prerequisite check(s) failed. See docs/development-setup.md."
    exit 1
}
Write-Host 'All requested prerequisite checks passed.'
exit 0
