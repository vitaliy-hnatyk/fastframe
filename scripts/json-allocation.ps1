# Allocation diagnostic using installed JDK 21, no external benchmark dependencies.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFrameMaven -Parameters @('-B','-ntp','-pl','framework-core','-am','-DskipTests','package')
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') `
      '-cp' (Join-Path $root 'framework-core\target\classes') `
      (Join-Path $root 'benchmarks\JsonAllocationProbe.java')
    if ($LASTEXITCODE -ne 0) { throw "Java allocation probe failed: $LASTEXITCODE" }
} finally { Pop-Location }
