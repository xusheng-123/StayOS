$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) { throw '.env already exists; keep the current credentials.' }
$randomBytes = New-Object byte[] 32
$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
$dbPassword = [BitConverter]::ToString($randomBytes).Replace('-', '')
@("STAYOS_DB_NAME=stayos", "STAYOS_DB_USER=stayos", "STAYOS_DB_PASSWORD=$dbPassword", 'STAYOS_DB_PORT=15432', 'STAYOS_TEST_DB_PORT=15433') | Set-Content -LiteralPath $envPath -Encoding utf8
Write-Host 'Created private .env. Do not commit it.'
