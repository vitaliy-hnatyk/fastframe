# FastFrame v0.22 database/Redis comparison (Windows PowerShell 5.1+).
param(
    [ValidateSet('fastframe','micronaut','laravel')][string[]] $Frameworks = @('fastframe','micronaut','laravel'),
    [ValidateSet('select','insert','update','transaction','cache_get','cache_set')][string[]] $Cases = @('select','insert','update','transaction','cache_get','cache_set'),
    [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane',
    [int] $LaravelWorkers = 4,
    [string] $Duration = '30s',
    [string] $Warmup = '5s',
    [int] $Concurrency = 64,
    [int] $Repeats = 3,
    [int] $DbPool = 16,
    [int] $IoWorkers = 32,
    [string] $Heap = '512m',
    [int] $Port = 18080,
    [switch] $SkipBuild,
    [switch] $StopServices
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $root 'scripts\windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Desktop / docker compose not found' }
    if (-not $SkipBuild) {
        $buildArgs = @{}
        if ($Frameworks -contains 'laravel') { $buildArgs.IncludeLaravel = $true; $buildArgs.LaravelServer = $LaravelServer }
        & (Join-Path $PSScriptRoot 'build.ps1') @buildArgs
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    $argsList = @('benchmarks/db_redis.py','--frameworks') + $Frameworks
    $argsList += @('--cases') + $Cases
    $argsList += @('--laravel-server',$LaravelServer,'--laravel-workers',[string]$LaravelWorkers)
    $argsList += @('--duration',$Duration,'--warmup',$Warmup,'--concurrency',[string]$Concurrency,
        '--repeats',[string]$Repeats,'--db-pool',[string]$DbPool,'--io-workers',[string]$IoWorkers,
        '--heap',$Heap,'--port',[string]$Port)
    if ($StopServices) { $argsList += '--stop-services' }
    Invoke-FastFramePython -Parameters $argsList
    if ($LASTEXITCODE -ne 0) { throw "DB/Redis benchmark failed: exit code $LASTEXITCODE" }
} finally { Pop-Location }
