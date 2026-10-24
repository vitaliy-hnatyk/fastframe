param([ValidateRange(1024,65535)][int] $Port = 18080)
# Check compiled FastFrame and Micronaut HTTP endpoints on Windows (no hey required).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $root 'scripts\windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFramePython -Parameters @('benchmarks/preflight.py', '--port', [string] $Port)
} finally {
    Pop-Location
}
