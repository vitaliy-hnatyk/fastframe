# Build and verify storage and HTTP client + demo on Windows with JDK 21.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFrameMaven -Parameters @('-B','-ntp','-pl','framework-storage,framework-storage-s3,framework-http-client,demo-app','-am','clean','verify')
} finally { Pop-Location }
