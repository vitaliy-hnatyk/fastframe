# Async HTTP I/O and HS256 JWT comparison (Windows PowerShell 5.1+/PowerShell 7).
param(
    [ValidateSet('fastframe','micronaut','laravel')][string[]] $Frameworks = @('fastframe','micronaut','laravel'),
    [ValidateSet('async_10','async_50','async_200','jwt')][string[]] $Cases = @('async_10','async_50','async_200','jwt'),
    [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane',
    [int] $LaravelWorkers = 4,
    [string] $Duration = '30s',
    [string] $Warmup = '5s',
    [int] $Concurrency = 64,
    [int] $Repeats = 3,
    [string] $Heap = '512m',
    [int] $Port = 18080,
    [int] $UpstreamPort = 19090,
    [switch] $SkipBuild
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
    $argsList = @('benchmarks/async_jwt.py','--frameworks') + $Frameworks
    $argsList += @('--cases') + $Cases
    $argsList += @('--laravel-server',$LaravelServer,'--laravel-workers',[string]$LaravelWorkers)
    $argsList += @('--duration',$Duration,'--warmup',$Warmup,'--concurrency',[string]$Concurrency,
        '--repeats',[string]$Repeats,'--heap',$Heap,'--port',[string]$Port,
        '--upstream-port',[string]$UpstreamPort)
    Invoke-FastFramePython -Parameters $argsList
    if ($LASTEXITCODE -ne 0) { throw "Async/JWT benchmark failed: exit code $LASTEXITCODE" }
} finally { Pop-Location }
