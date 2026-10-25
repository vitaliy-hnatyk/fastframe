$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFrameMaven -Parameters @('-B','-ntp','-pl','framework-application,framework-events','-am','clean','verify')
    Write-Host 'FastFrame v0.17 Windows bootstrap tests: PASS'
} finally { Pop-Location }
