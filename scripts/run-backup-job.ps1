param(
    [Parameter(Mandatory)][string]$Container,
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$StatusDirectory,
    [string]$User = 'stayos',
    [string]$KeyEnvPath = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env')
)
. "$PSScriptRoot/database-common.ps1"
$statusPath = [IO.Path]::GetFullPath($StatusDirectory)
if (!(Test-Path -LiteralPath $statusPath)) { [void](New-Item -ItemType Directory -Path $statusPath) }
$attempt = @{ attemptedUtc=[DateTime]::UtcNow.ToString('o'); status='FAILED'; database=$Database }
try {
    Assert-DatabaseTarget $Container $Database $User
    $fingerprint = & "$PSScriptRoot/show-guest-key-fingerprint.ps1" -EnvPath $KeyEnvPath
    $result = & "$PSScriptRoot/backup-database.ps1" -Container $Container -Database $Database -User $User -OutputDirectory $OutputDirectory -GuestKeyFingerprint $fingerprint
    $attempt.status='SUCCESS';$attempt.completedUtc=[DateTime]::UtcNow.ToString('o');$attempt.sha256=$result.Sha256
    $attempt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $statusPath 'last-success.json') -Encoding utf8
    $result
} catch { throw '备份任务失败，请检查 last-attempt.json、容器、磁盘和备份目录；不要删除最后成功的归档。' }
finally {
    $attempt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $statusPath 'last-attempt.json') -Encoding utf8
}
