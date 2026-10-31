[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)][string]$Container,
    [Parameter(Mandatory)][string]$Database,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$StatusDirectory,
    [string]$KeyEnvPath = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env'),
    [ValidatePattern('^([01]\d|2[0-3]):[0-5]\d$')][string]$At = '03:00',
    [ValidatePattern('^StayOS-Backup-[a-zA-Z0-9_-]{1,40}$')][string]$TaskName = 'StayOS-Backup-Daily'
)
. "$PSScriptRoot/database-common.ps1"
Assert-DatabaseTarget $Container $Database 'stayos'
if ($env:OS -ne 'Windows_NT') { throw '此安装脚本仅支持 Windows 任务计划程序。' }
$job = Join-Path $PSScriptRoot 'run-backup-job.ps1'
foreach ($path in @($job,$OutputDirectory,$StatusDirectory,$KeyEnvPath)) { if ($path -match '["\r\n]') { throw '路径不能包含引号或换行。' } }
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) { throw '同名任务已存在，拒绝覆盖。' }
$arguments = '-NoProfile -NonInteractive -File "{0}" -Container "{1}" -Database "{2}" -OutputDirectory "{3}" -StatusDirectory "{4}" -KeyEnvPath "{5}"' -f $job,$Container,$Database,[IO.Path]::GetFullPath($OutputDirectory),[IO.Path]::GetFullPath($StatusDirectory),[IO.Path]::GetFullPath($KeyEnvPath)
if ($PSCmdlet.ShouldProcess($TaskName,'创建每日备份任务；只在当前 Windows 用户已登录且 Docker 可用时运行')) {
    $action = New-ScheduledTaskAction -Execute (Get-Command pwsh).Source -Argument $arguments -WorkingDirectory (Split-Path $PSScriptRoot -Parent)
    $trigger = New-ScheduledTaskTrigger -Daily -At $At
    $principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings | Select-Object TaskName,State
}
