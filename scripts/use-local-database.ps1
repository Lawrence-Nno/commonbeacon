# Dot-source to configure a host-run backend from the same .env used by Compose.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Copy .env.example to .env and set a local password first.'
}
$settings = @{
    POSTGRES_DB = 'commonbeacon'
    POSTGRES_USER = 'commonbeacon'
    POSTGRES_PORT = '5432'
    DEMO_SEED_ENABLED = 'false'
    DEMO_PASSWORD = ''
}
foreach ($line in Get-Content -LiteralPath $envPath) {
    $valueLine = $line.Trim()
    if (-not $valueLine -or $valueLine.StartsWith('#')) { continue }
    $parts = $valueLine -split '=', 2
    if ($parts.Count -ne 2) { throw 'Invalid .env entry; use KEY=value lines.' }
    $key = $parts[0].Trim()
    if ($key -in @('POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD', 'POSTGRES_PORT', 'DEMO_SEED_ENABLED', 'DEMO_PASSWORD')) {
        $value = $parts[1].Trim()
        if ($value.Contains('"') -or $value.Contains("'") -or $value.Contains('$') -or $value.Contains('#')) {
            throw "Use an unquoted literal value without interpolation or inline comments for $key."
        }
        $settings[$key] = $value
    }
}
if (-not $settings.POSTGRES_PASSWORD -or $settings.POSTGRES_PASSWORD -eq 'replace-for-local-development') {
    throw 'Set a non-placeholder POSTGRES_PASSWORD in .env.'
}
if ($settings.POSTGRES_DB -notmatch '^[a-zA-Z0-9_]+$' -or
    $settings.POSTGRES_USER -notmatch '^[a-zA-Z0-9_]+$') {
    throw 'Use letters, digits and underscores for local database and user names.'
}
$portNumber = 0
if (-not [int]::TryParse($settings.POSTGRES_PORT, [ref]$portNumber) -or
    $portNumber -lt 1 -or $portNumber -gt 65535) {
    throw 'POSTGRES_PORT must be between 1 and 65535.'
}
# Clear inherited Compose overrides so the host app and Compose use this file.
foreach ($key in $settings.Keys) {
    [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process')
}
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$portNumber/$($settings.POSTGRES_DB)"
$env:SPRING_DATASOURCE_USERNAME = $settings.POSTGRES_USER
$env:SPRING_DATASOURCE_PASSWORD = $settings.POSTGRES_PASSWORD
$env:SPRING_PROFILES_ACTIVE = 'local'
Write-Host 'Selected local profile. Configured the host backend database connection from .env (password not displayed).'
