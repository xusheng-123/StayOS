param([switch]$Browser)
. "$PSScriptRoot\env.ps1"
# The full suite launches PowerShell subprocesses; check tools before touching containers.
foreach ($tool in @('java', 'mvn', 'pwsh', 'docker')) {
    if (!(Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Required test tool '$tool' was not found. See README.md for Java 21, Maven and PowerShell 7 requirements." }
}
Push-Location $projectRoot
try {
    & docker compose --env-file .env -f deploy/compose.yaml --profile test up -d --wait --no-deps postgres-test
    if ($LASTEXITCODE -ne 0) { throw 'Test database failed to start.' }
    $testArgs = @('-B', '-f', 'backend/pom.xml', 'test')
    if ($Browser) { $testArgs += '-Dstayos.e2e=true' }
    & mvn @testArgs
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
} finally { Pop-Location }
