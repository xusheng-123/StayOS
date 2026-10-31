param(
    [Parameter(Mandatory)][string]$Container,
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][string]$ConfirmTarget,
    [Parameter(Mandatory)][string]$BackupPath,
    [string]$User = 'stayos',
    [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedGuestKeyFingerprint
)
. "$PSScriptRoot/database-common.ps1"
Assert-DatabaseTarget $Container $Database $User -Restore
if ($ConfirmTarget -cne $Database) { throw '必须用 -ConfirmTarget 再次明确填写完整恢复数据库名称。' }
$archive = (Resolve-Path -LiteralPath $BackupPath).Path
$manifest = Get-Content -LiteralPath "$archive.json" -Raw | ConvertFrom-Json
if ($ExpectedGuestKeyFingerprint -and $manifest.guestKeyFingerprint -ne $ExpectedGuestKeyFingerprint) { throw '归档密钥指纹缺失或不匹配，恢复已拒绝；不要更换原密钥来尝试解密。' }
if ($manifest.format -ne 'StayOS/pg_dump/custom/v1' -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $manifest.sha256 -or (Get-Item -LiteralPath $archive).Length -ne $manifest.bytes) { throw '备份格式、长度或 SHA256 校验失败，恢复已拒绝。' }
$existing = Invoke-DatabaseTool @('exec', $Container, 'psql', '-U', $User, '-d', 'postgres', '-At', '-c', "select 1 from pg_database where datname='$Database'")
if ($existing) { throw '恢复目标已存在；拒绝覆盖、清空或删除任何已有数据库。请选择新的隔离库名。' }
$timer = [Diagnostics.Stopwatch]::StartNew()
$remote = "/tmp/stayos-restore-$([guid]::NewGuid().ToString('N')).dump"
try {
    [void](Invoke-DatabaseTool @('cp', $archive, "${Container}:$remote"))
    [void](Invoke-DatabaseTool @('exec', $Container, 'pg_restore', '--list', $remote))
    [void](Invoke-DatabaseTool @('exec', $Container, 'createdb', '-U', $User, '-O', $User, $Database))
    [void](Invoke-DatabaseTool @('exec', $Container, 'pg_restore', '-U', $User, '-d', $Database, '--single-transaction', '--exit-on-error', '--no-owner', '--no-privileges', $remote))
    $timer.Stop()
    [pscustomobject]@{ Status = 'SUCCESS'; Database = $Database; Seconds = [math]::Round($timer.Elapsed.TotalSeconds, 3); KeyIncluded = $false }
} finally { & docker exec $Container rm -f -- $remote 2>$null | Out-Null }
