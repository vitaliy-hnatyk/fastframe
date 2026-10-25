# Redis-backed separate worker or scheduler for Windows, using only JDK 21 in this session.
param(
    [ValidateSet('dispatch','worker','scheduler','failures')][string] $Command = 'worker',
    [string] $Payload = 'Hello from Windows FastFrame',
    [switch] $SkipBuild
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
if (-not $env:FF_REDIS_URI) { throw 'Set FF_REDIS_URI (for example redis://127.0.0.1:6379) first.' }
Push-Location $root
try {
    if (-not $SkipBuild) {
        Invoke-FastFrameMaven -Parameters @('-B','-ntp','-pl','queue-demo','-am','clean','package')
    }
    $jar=Join-Path $root 'queue-demo\target\queue-demo-0.21.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Missing $jar; rebuild without -SkipBuild" }
    $javaArgs=@('-jar',$jar,$Command)
    if ($Command -eq 'dispatch') { $javaArgs+= $Payload }
    Write-Host "FastFrame Redis Queue command: $Command (Ctrl+C to stop worker/scheduler)"
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') @javaArgs
    if ($LASTEXITCODE -ne 0) { throw "Queue command failed (exit code $LASTEXITCODE)" }
} finally { Pop-Location }
