# Build and test the cache module and the demo using local JDK 21 on Windows.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFrameMaven -Parameters @('-B', '-ntp', '-pl', 'framework-cache,demo-app', '-am', 'clean', 'verify')
} finally { Pop-Location }
