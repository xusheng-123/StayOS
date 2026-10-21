$ErrorActionPreference = 'Stop'

function Invoke-DatabaseTool {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
    # Never print stderr: a driver/tool failure can contain connection details.
    $result = & docker @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw '数据库工具执行失败，请检查容器、数据库名称及权限；未输出连接凭据。' }
    return $result
}
function Assert-DatabaseTarget([string]$Container, [string]$Database, [string]$User, [switch]$Restore) {
    if ($Container -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}$' -or $Database -notmatch '^[a-z][a-z0-9_]{0,62}$' -or $User -notmatch '^[a-z][a-z0-9_]{0,62}$') { throw '容器、数据库或用户名称格式不正确。' }
    $info = Invoke-DatabaseTool @('inspect', $Container) | ConvertFrom-Json
    if ($info.Count -ne 1 -or !$info[0].State.Running -or $info[0].Config.Labels.'com.docker.compose.project' -ne 'stayos') { throw '仅支持运行中的 StayOS Compose 数据库容器。' }
    $service = $info[0].Config.Labels.'com.docker.compose.service'
    if ($service -notin @('postgres', 'postgres-test')) { throw '目标不是已知 StayOS PostgreSQL 服务。' }
    if ($Restore -and ($service -ne 'postgres-test' -or $Database -notmatch '^stayos_restore_[a-z0-9_]{1,40}$')) { throw '恢复仅允许隔离 postgres-test 容器和 stayos_restore_ 开头的新数据库，拒绝开发库。' }
}
function Protect-BackupPath([string]$Path) {
    if ($env:OS -eq 'Windows_NT') {
        # Build a DACL-only descriptor: copying owner/SACL fields can require
        # SeSecurityPrivilege in an ordinary child PowerShell process.
        $acl = if (Test-Path -LiteralPath $Path -PathType Container) { [System.Security.AccessControl.DirectorySecurity]::new() } else { [System.Security.AccessControl.FileSecurity]::new() }
        $acl.SetAccessRuleProtection($true, $false)
        $rights = [System.Security.AccessControl.FileSystemRights]::FullControl
        $inheritance = if (Test-Path -LiteralPath $Path -PathType Container) { [System.Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit' } else { [System.Security.AccessControl.InheritanceFlags]::None }
        foreach ($sid in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User, [System.Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
            $acl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($sid, $rights, $inheritance, [System.Security.AccessControl.PropagationFlags]::None, [System.Security.AccessControl.AccessControlType]::Allow))
        }
        Set-Acl -LiteralPath $Path -AclObject $acl
    } else { throw '本脚本的文件访问保护目前仅验证 Windows，请在 Windows PowerShell 中运行。' }
}
