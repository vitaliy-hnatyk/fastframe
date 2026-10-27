# JSON response size scaling, PowerShell 5.1+; Java 21 selected locally.
param(
    [ValidateSet('fastframe','micronaut','laravel')][string[]] $Frameworks = @('fastframe','micronaut','laravel'),
    [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane',
    [ValidateRange(1,512)][int] $LaravelWorkers = 4,
    [ValidateSet('1kb','10kb','100kb','1mb')][string[]] $Sizes = @('1kb','10kb','100kb','1mb'),
    [string] $Duration = '30s',
    [string] $Warmup = '5s',
    [ValidateRange(1,4096)][int] $Concurrency = 64,
    [ValidateRange(1,100)][int] $Repeats = 3,
    [ValidateRange(1024,65535)][int] $Port = 18080,
    [string] $Heap = '512m',
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
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    if (-not $SkipPreflight) {
        $preflight = @('benchmarks/preflight.py','--port',[string]$Port,'--frameworks') + $Frameworks
        $preflight += @('--laravel-server',$LaravelServer,'--laravel-workers',[string]$LaravelWorkers)
        Invoke-FastFramePython -Parameters $preflight
        if ($LASTEXITCODE -ne 0) { throw 'Preflight failed' }
    }
    if (-not (Get-Command hey.exe -ErrorAction SilentlyContinue)) {
        throw 'hey.exe not found in PATH; install github.com/rakyll/hey'
    }
    $argsList = @('benchmarks/json_sizes.py','--frameworks') + $Frameworks
    $argsList += @('--laravel-server',$LaravelServer,'--laravel-workers',[string]$LaravelWorkers)
    $argsList += @('--sizes') + $Sizes
    $argsList += @('--duration',$Duration,'--warmup',$Warmup,'--concurrency',[string]$Concurrency,
        '--repeats',[string]$Repeats,'--port',[string]$Port,'--heap',$Heap)
    if ($Output) { $argsList += @('--output',$Output) }
    if ($KeepRaw) { $argsList += @('--keep-raw') }
    Invoke-FastFramePython -Parameters $argsList
    if ($LASTEXITCODE -ne 0) { throw "JSON size benchmark failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
