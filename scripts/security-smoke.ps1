$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Set-Location $script:FastFrameWindowsRoot
Invoke-FastFrameMaven -Parameters @('-B','-ntp','-pl','framework-security,framework-netty','-am','test')
Write-Host 'FastFrame HTTP Security Maven tests completed.'
