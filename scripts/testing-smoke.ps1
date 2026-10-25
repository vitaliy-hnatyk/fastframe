$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFrameMaven -Parameters @('-B', '-ntp', '-pl', 'framework-testing,demo-app', '-am', 'clean', 'verify')
    Write-Host 'FastFrame v0.19 HTTP feature, auth, H2 factory and mock tests: PASS'
} finally { Pop-Location }
