# Build and start the sample FastFrame HTTP server on Windows.
param(
    [ValidateRange(1,65535)][int] $Port = 8080,
    [switch] $SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    if (-not $SkipBuild) {
        Invoke-FastFrameMaven -Parameters @('-B', '-ntp', '-pl', 'demo-app', '-am', 'clean', 'package')
    }
    $jar = Join-Path $root 'demo-app\target\demo-app-0.7.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Missing $jar. Build it without -SkipBuild first."
    }
    $env:PORT = [string] $Port
    Write-Host "Starting FastFrame demo on http://127.0.0.1:$Port (Ctrl+C to stop)"
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-jar' $jar
    if ($LASTEXITCODE -ne 0) { throw "FastFrame exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
