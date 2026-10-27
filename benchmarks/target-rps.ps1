# PowerShell 5.1+; runs Python k6 benchmark with Java 21 selected for this process.
param(
    [ValidateSet('fastframe','micronaut','laravel')][string[]] $Frameworks = @('fastframe','micronaut','laravel'),
    [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane',
    [ValidateRange(1,512)][int] $LaravelWorkers = 4,
    [ValidateRange(1,1000000)][int[]] $Rates = @(1000,2000,4000,8000,12000,16000,20000,24000,30000),
    [ValidateSet('plain','json','route','echo')][string[]] $Cases = @('plain','json','route','echo'),
    [string] $Duration = '60s',
    [string] $Warmup = '5s',
    [ValidateRange(1,100)][int] $Repeats = 1,
    [ValidateRange(1,100000)][int] $Vus = 128,
    [ValidateRange(1,100000)][int] $MaxVus = 512,
    [ValidateRange(1024,65535)][int] $Port = 18080,
    [string] $Heap = '256m',
    [string] $Output = '',
    [switch] $KeepK6,
    [switch] $SkipBuild,
    [switch] $SkipPreflight
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $root 'scripts\windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    if (-not $SkipBuild) {
        $buildArgs = @{}
        if ($Frameworks -contains 'laravel') { $buildArgs.IncludeLaravel = $true; $buildArgs.LaravelServer = $LaravelServer }
        & (Join-Path $PSScriptRoot 'build.ps1') @buildArgs
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark build failed' }
    }
    if (-not $SkipPreflight) {
        $preflight = @('benchmarks/preflight.py', '--port', [string]$Port, '--frameworks') + $Frameworks
        $preflight += @('--laravel-server', $LaravelServer, '--laravel-workers', [string]$LaravelWorkers)
        Invoke-FastFramePython -Parameters $preflight
        if ($LASTEXITCODE -ne 0) { throw 'Preflight failed' }
    }
    if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) { throw 'k6 is missing. Install Grafana k6 and add it to PATH.' }
    $params = @('benchmarks/target_rps.py', '--frameworks') + $Frameworks
    $params += @('--laravel-server', $LaravelServer, '--laravel-workers', [string]$LaravelWorkers,
        '--rates') + @($Rates | ForEach-Object { [string]$_ })
    $params += @('--cases') + $Cases
    $params += @('--duration', $Duration, '--warmup', $Warmup, '--repeats', [string]$Repeats,
        '--vus', [string]$Vus, '--max-vus', [string]$MaxVus,
        '--port', [string]$Port, '--heap', $Heap)
    if ($Output) { $params += @('--output', $Output) }
    if ($KeepK6) { $params += @('--keep-k6') }
    Invoke-FastFramePython -Parameters $params
    if ($LASTEXITCODE -ne 0) { throw "Target RPS benchmark failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
