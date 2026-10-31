param([string]$EnvPath = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env'))
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $EnvPath -PathType Leaf)) { throw '未找到私有密钥配置文件。' }
$keyLine = @(Get-Content -LiteralPath $EnvPath | Where-Object { $_ -match '^STAYOS_GUEST_ENCRYPTION_KEY=' })
if ($keyLine.Count -ne 1) { throw '必须且只能配置一个住客加密密钥。' }
try {
    $keyBytes = [Convert]::FromBase64String($keyLine[0].Substring('STAYOS_GUEST_ENCRYPTION_KEY='.Length))
    if ($keyBytes.Length -ne 32) { throw 'Invalid key length' }
    [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($keyBytes)).ToLowerInvariant()
} catch { throw '密钥格式不正确；未输出密钥。' }
finally { if ($keyBytes) { [Array]::Clear($keyBytes,0,$keyBytes.Length) }; $keyLine=$null }
