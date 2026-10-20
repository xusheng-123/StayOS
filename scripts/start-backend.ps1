. "$PSScriptRoot\env.ps1"
Push-Location (Join-Path $projectRoot 'backend')
try { & mvn spring-boot:run; if ($LASTEXITCODE -ne 0) { throw 'Backend failed.' } }
finally { Pop-Location }
