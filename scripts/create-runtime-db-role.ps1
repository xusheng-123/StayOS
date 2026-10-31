param(
    [Parameter(Mandatory)][string]$Container,
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9_]{2,62}$')][string]$RuntimeRole,
    [string]$User = 'stayos',
    [Security.SecureString]$Password
)
. "$PSScriptRoot/database-common.ps1"
Assert-DatabaseTarget $Container $Database $User
if ($RuntimeRole -eq $User -or $RuntimeRole -eq 'postgres') { throw '运行账号必须与迁移账号不同。' }
$exists = Invoke-DatabaseTool @('exec',$Container,'psql','-U',$User,'-d',$Database,'-At','-c',"select count(*) from pg_roles where rolname='$RuntimeRole'")
if ($exists.Trim() -ne '0') { throw '目标角色已经存在，拒绝修改其密码或权限。' }
if (!$Password) { $Password = Read-Host '为新的运行账号输入独立强密码（不回显）' -AsSecureString }
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ($plain.Length -lt 20 -or $plain.Length -gt 128 -or $plain -match '[\x00-\x1f\x7f]') { throw '请使用 20—128 字符的独立数据库密码。' }
    $literal = $plain.Replace("'", "''")
    $grants = Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot -Parent) 'deploy/runtime-grants.sql') -Raw
    $sql = "SET standard_conforming_strings=on;`nCREATE ROLE $RuntimeRole LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '$literal';`n$grants"
    # Password travels via stdin only, never process arguments, a SQL file, or terminal output.
    $sql | & docker exec -i $Container psql -U $User -d $Database -v ON_ERROR_STOP=1 -v "runtime_role=$RuntimeRole" -v "database_name=$Database" --single-transaction 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '运行账号创建失败，事务已回滚；未输出凭据或原始 SQL。' }
    Write-Output '新的受限运行账号已创建；现有应用账号、数据库及配置未改变。'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $plain = $null; $literal = $null; $sql = $null
}
