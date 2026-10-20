param([switch]$Browser)
. "$PSScriptRoot\env.ps1"
Push-Location $projectRoot
try {
    & docker compose --env-file .env -f deploy/compose.yaml --profile test up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Test database failed to start.' }
    $testArgs = @('-B', '-f', 'backend/pom.xml', 'test')
    if ($Browser) { $testArgs += '-Dstayos.e2e=true' }
    & mvn @testArgs
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
} finally { Pop-Location }
