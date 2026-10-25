# Profile a real HTTP benchmark server under hey load with JDK 21 Flight Recorder.
# Run from a trusted local PowerShell session after benchmarks/build.ps1.
param(
    [ValidateSet('fastframe','micronaut')][string] $Framework = 'fastframe',
    [ValidateSet('plain','json','route','echo')][string] $Case = 'json',
    [ValidateRange(1,1024)][int] $Concurrency = 64,
    [string] $Duration = '30s',
    [string] $Warmup = '10s',
    [ValidateRange(1024,65535)][int] $Port = 18080,
    [string] $Heap = '256m',
    [string] $Output = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $root 'scripts\windows-env.ps1')
Use-FastFrameJava21
if (-not (Get-Command hey.exe -ErrorAction SilentlyContinue)) {
    throw 'hey.exe is required for JFR load profiling (same as benchmarks/run.ps1)'
}
$artifact = if ($Framework -eq 'fastframe') { 'benchmark-fastframe' } else { 'benchmark-micronaut' }
$projectDir = if ($Framework -eq 'fastframe') { 'fastframe-app' } else { 'micronaut-app' }
$jarDir = Join-Path $PSScriptRoot "$projectDir\target"
$jars = @(Get-ChildItem -LiteralPath $jarDir -File -Filter "$artifact-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike 'original-*' })
if ($jars.Count -ne 1) { throw "Expected exactly one built $artifact jar in $jarDir. Run benchmarks/build.ps1 first." }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$jcmd = Join-Path $env:JAVA_HOME 'bin\jcmd.exe'
$jfr = Join-Path $env:JAVA_HOME 'bin\jfr.exe'
if (!(Test-Path -LiteralPath $jcmd)) { throw 'JDK 21 jcmd.exe not found' }
$path = @{plain='/bench/plain';json='/bench/json';route='/bench/route/123';echo='/bench/echo'}[$Case]
if (-not $Output) { $Output = Join-Path $PSScriptRoot 'jfr' }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$Output = (Resolve-Path -LiteralPath $Output).Path
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$dump = Join-Path $Output "$Framework-$Case-$stamp.jfr"
$stdout = Join-Path $Output "$Framework-$Case-$stamp.out.log"
$stderr = Join-Path $Output "$Framework-$Case-$stamp.err.log"
$env:PORT = [string]$Port
$arguments = @("-Xms$Heap", "-Xmx$Heap", '-XX:+UseG1GC',
    '-XX:StartFlightRecording=name=load,settings=profile,maxsize=256m', '-jar', ('"' + $jars[0].FullName + '"'))
$server = $null
try {
    $server = Start-Process -FilePath $java -ArgumentList $arguments -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $base = "http://127.0.0.1:$Port"
    $ready = $false
    foreach ($attempt in 1..100) {
        if ($server.HasExited) { throw "Server exited with code $($server.ExitCode); see $stderr" }
        try {
            $health = Invoke-WebRequest -Uri "$base/bench/plain" -UseBasicParsing -TimeoutSec 2
            if ($health.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw "Server not ready on $base; see $stderr" }
    $url = "$base$path"
    $heyArgs = @('-c', [string]$Concurrency, '-z', $Warmup, '-t', '15')
    if ($Case -eq 'echo') { $heyArgs += @('-m','POST','-T','application/json','-d','{"message":"hello"}') }
    $heyArgs += $url
    & hey.exe @heyArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'hey warmup failed' }
    $heyArgs[3] = $Duration # -z <duration> is argument index 3
    & hey.exe @heyArgs | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'hey load failed' }
    & $jcmd $server.Id 'JFR.dump' 'name=load' "filename=$dump"
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $dump)) { throw 'Failed to dump JFR recording' }
    & $jcmd $server.Id 'JFR.stop' 'name=load' | Out-Null
    Write-Host "JFR written: $dump"
    & $jfr summary $dump
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
}
