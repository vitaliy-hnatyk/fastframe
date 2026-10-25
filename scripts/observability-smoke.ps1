# Java 21 / Windows local Netty and observability tests.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFrameMaven -Parameters @('-B','-ntp','-pl','framework-observability,framework-netty,demo-app','-am','clean','verify')
} finally { Pop-Location }
