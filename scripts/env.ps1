$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectRoot '.env'
if (!(Test-Path -LiteralPath $envPath)) { throw 'Run scripts/init-local.ps1 first.' }
foreach ($line in Get-Content -LiteralPath $envPath) {
    if ($line -match '^([A-Z_]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') }
}
# Portable tools affect this process only; retain support for the original versioned folders.
$toolsRoot = Join-Path $env:LOCALAPPDATA 'StayOS\tools'
$portableJdk = Join-Path $toolsRoot 'java-21'
if (!(Test-Path $portableJdk)) { $portableJdk = Join-Path $toolsRoot 'jdk-21.0.12.1+1' }
if (Test-Path $portableJdk) { $env:JAVA_HOME = $portableJdk; $env:PATH = "$portableJdk\bin;$env:PATH" }
$portableMaven = Join-Path $toolsRoot 'maven\bin'
if (!(Test-Path $portableMaven)) { $portableMaven = Join-Path $toolsRoot 'apache-maven-3.9.16\bin' }
if (Test-Path $portableMaven) { $env:PATH = "$portableMaven;$env:PATH" }
$portablePowerShell = Join-Path $toolsRoot 'powershell-7'
if (Test-Path $portablePowerShell) { $env:PATH = "$portablePowerShell;$env:PATH" }
