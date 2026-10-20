. "$PSScriptRoot\env.ps1"
$jarPath = Join-Path $projectRoot 'backend\target\stayos-0.1.0-SNAPSHOT.jar'
if (!(Test-Path $jarPath)) { throw 'Build first: mvn -f backend/pom.xml -DskipTests package' }
# Keep Java attached to the real terminal: System.console() must be available for hidden password input.
& java -jar $jarPath --spring.main.web-application-type=none --stayos.bootstrap-admin=true
if ($LASTEXITCODE -ne 0) { throw 'Administrator initialization failed. No password was logged.' }
