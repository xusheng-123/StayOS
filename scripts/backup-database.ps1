param(
    [Parameter(Mandatory)][string]$Container,
    [Parameter(Mandatory)][string]$Database,
    [string]$User = 'stayos',
    [string]$OutputDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'backups'),
    [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$GuestKeyFingerprint
)
. "$PSScriptRoot/database-common.ps1"
Assert-DatabaseTarget $Container $Database $User
$timer = [Diagnostics.Stopwatch]::StartNew()
$baseDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (!(Test-Path -LiteralPath $baseDirectory)) { [void](New-Item -ItemType Directory -Path $baseDirectory) }
if (!(Test-Path -LiteralPath $baseDirectory -PathType Container)) { throw '备份目标必须是目录。' }
$token = [guid]::NewGuid().ToString('N')
$name = "$Database-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ'))-$token"
# Change access only on this newly created backup directory, never on an
# existing user-selected folder (which could also contain unrelated files).
$directory = Join-Path $baseDirectory $name
[void](New-Item -ItemType Directory -Path $directory -ErrorAction Stop)
Protect-BackupPath $directory
$remote = "/tmp/stayos-$token.dump"
$partial = Join-Path $directory "$name.partial"
$archive = Join-Path $directory "$name.dump"
$manifest = "$archive.json"
try {
    [void](Invoke-DatabaseTool @('exec', $Container, 'touch', $remote))
    [void](Invoke-DatabaseTool @('exec', $Container, 'chmod', '600', $remote))
    [void](Invoke-DatabaseTool @('exec', $Container, 'pg_dump', '-U', $User, '-d', $Database, '-Fc', '--no-owner', '--no-privileges', '-f', $remote))
    [void](Invoke-DatabaseTool @('exec', $Container, 'pg_restore', '--list', $remote))
    [void](Invoke-DatabaseTool @('cp', "${Container}:$remote", $partial))
    Protect-BackupPath $partial
    if ((Get-Item -LiteralPath $partial).Length -lt 100) { throw '备份文件异常，未标记成功。' }
    $hash = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash
    $version = (Invoke-DatabaseTool @('exec', $Container, 'psql', '-U', $User, '-d', $Database, '-At', '-c', 'show server_version')).Trim()
    Move-Item -LiteralPath $partial -Destination $archive -ErrorAction Stop
    $meta = @{ format = 'StayOS/pg_dump/custom/v1'; database = $Database; createdUtc = [DateTime]::UtcNow.ToString('o'); sha256 = $hash; bytes = (Get-Item -LiteralPath $archive).Length; postgresVersion = $version; guestKeyId = 'v1'; keyIncluded = $false }
    if ($GuestKeyFingerprint) { $meta.guestKeyFingerprint=$GuestKeyFingerprint.ToLowerInvariant() }
    $meta | ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding utf8 -NoNewline
    Protect-BackupPath $manifest
    $timer.Stop()
    [pscustomobject]@{ Status = 'SUCCESS'; BackupPath = $archive; ManifestPath = $manifest; Sha256 = $hash; Seconds = [math]::Round($timer.Elapsed.TotalSeconds, 3) }
} finally {
    & docker exec $Container rm -f -- $remote 2>$null | Out-Null
    if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial }
}
