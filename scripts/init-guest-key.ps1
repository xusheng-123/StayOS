$ErrorActionPreference = 'Stop'
$envPath = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
if (!(Test-Path -LiteralPath $envPath)) { throw 'Run scripts/init-local.ps1 first.' }
if (Select-String -LiteralPath $envPath -Pattern '^STAYOS_GUEST_ENCRYPTION_KEY=.+$' -Quiet) {
    Write-Host 'Guest encryption key already exists; kept unchanged.'
    exit 0
}
$guestKeyBytes = New-Object byte[] 32
$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($guestKeyBytes) } finally { $generator.Dispose() }
Add-Content -LiteralPath $envPath -Value @('', ('STAYOS_GUEST_ENCRYPTION_KEY=' + [Convert]::ToBase64String($guestKeyBytes))) -Encoding utf8
Write-Host 'Created private guest encryption key in .env. Keep it with encrypted-data backups; never commit or replace it casually.'
