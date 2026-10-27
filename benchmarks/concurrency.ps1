# v0.22 HTTP concurrency scaling on Windows PowerShell 5.1+.
param(
    [ValidateSet('fastframe','micronaut','laravel')][string[]] $Frameworks = @('fastframe','micronaut','laravel'),
    [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane',
    [ValidateRange(1,512)][int] $LaravelWorkers = 4,
    [ValidateRange(1,4096)][int[]] $Levels = @(1,8,16,32,64,128,256,512,1024),
    [ValidateSet('plain','json','route','echo')][string[]] $Cases = @('plain','json','route','echo'),
    [string] $Duration = '30s',
    [string] $Warmup = '5s',
    [ValidateRange(1,100)][int] $Repeats = 1,
    [ValidateRange(1024,65535)][int] $Port = 18080,
    [string] $Heap = '256m',
    [string] $Output = '',
    [switch] $KeepRaw,
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
        if ($Frameworks -contains 'laravel') {
            $buildArgs.IncludeLaravel = $true
            $buildArgs.LaravelServer = $LaravelServer
        }
        & (Join-Path $PSScriptRoot 'build.ps1') @buildArgs
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark build failed' }
    }
    if (-not $SkipPreflight) {
        $preflightArgs = @('benchmarks/preflight.py', '--port', [string]$Port, '--frameworks') + $Frameworks
        $preflightArgs += @('--laravel-server', $LaravelServer, '--laravel-workers', [string]$LaravelWorkers)
        Invoke-FastFramePython -Parameters $preflightArgs
        if ($LASTEXITCODE -ne 0) { throw 'Preflight failed' }
    }
    if (-not (Get-Command hey.exe -ErrorAction SilentlyContinue)) {
        throw 'hey.exe was not found in PATH. Install it with: go install github.com/rakyll/hey@latest'
    }
    $runArgs = @('benchmarks/concurrency.py', '--frameworks') + $Frameworks
    $runArgs += @('--laravel-server', $LaravelServer, '--laravel-workers', [string]$LaravelWorkers,
        '--levels') + @($Levels | ForEach-Object { [string]$_ })
    $runArgs += @('--cases') + $Cases
    $runArgs += @('--duration', $Duration, '--warmup', $Warmup, '--repeats', [string]$Repeats,
        '--port', [string]$Port, '--heap', $Heap)
    if ($Output) { $runArgs += @('--output', $Output) }
    if ($KeepRaw) { $runArgs += @('--keep-raw') }
    Invoke-FastFramePython -Parameters $runArgs
    if ($LASTEXITCODE -ne 0) { throw "Concurrency benchmark failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
