param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [Parameter(Mandatory)][string]$StatusDirectory,
    [ValidateRange(1,168)][int]$MaxBackupAgeHours = 26
)
$ErrorActionPreference='Stop'
$checks=[ordered]@{ applicationReady=$false; backupLatestAttemptSucceeded=$false; backupRecent=$false }
$uri=$null
if (![Uri]::TryCreate($BaseUrl,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -notin @('http','https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw 'BaseUrl 必须是没有凭据、查询参数或片段的 HTTP/HTTPS 服务地址。' }
try {
    $response=Invoke-RestMethod -Uri ($BaseUrl.TrimEnd('/')+'/api/health/ready') -TimeoutSec 6
    $checks.applicationReady=($response.success -eq $true -and $response.data.status -eq 'UP')
} catch { }
try {
    $latest=Get-Content -LiteralPath (Join-Path $StatusDirectory 'last-attempt.json') -Raw | ConvertFrom-Json
    $success=Get-Content -LiteralPath (Join-Path $StatusDirectory 'last-success.json') -Raw | ConvertFrom-Json
    $checks.backupLatestAttemptSucceeded=($latest.status -eq 'SUCCESS')
    $completed=[DateTimeOffset]::Parse($success.completedUtc)
    $age=[DateTimeOffset]::UtcNow-$completed
    $checks.backupRecent=($success.status -eq 'SUCCESS' -and $age.TotalHours -ge 0 -and $age.TotalHours -le $MaxBackupAgeHours)
} catch { }
$healthy=($checks.Values -notcontains $false)
[pscustomobject]@{ healthy=$healthy; checkedUtc=[DateTime]::UtcNow.ToString('o'); checks=$checks } | ConvertTo-Json -Depth 3
if (!$healthy) { exit 1 }
