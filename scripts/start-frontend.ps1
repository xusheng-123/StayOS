$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location (Join-Path $projectRoot 'frontend')
try { & npm run dev; if ($LASTEXITCODE -ne 0) { throw 'Frontend failed.' } }
finally { Pop-Location }
