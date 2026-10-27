# JSON response size scaling, PowerShell 5.1+; Java 21 selected locally.
param(
    [ValidateSet('fastframe','micronaut','laravel')][string[]] $Frameworks = @('fastframe','micronaut','laravel'),
    [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane',
    [ValidateRange(1,512)][int] $LaravelWorkers = 4,
    # PowerShell parses unquoted 1kb/10kb/100kb/1mb as byte integers, before binding.
    [object[]] $Sizes = @('1kb','10kb','100kb','1mb'),
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
# Convert both quoted size labels and PowerShell's unquoted numeric byte literals
# to the exact labels accepted by benchmarks/json_sizes.py. Preserve order.
# Reject all other values, including arbitrary byte counts.
$sizeAliases = @{
    '1kb' = '1kb'; '1024' = '1kb'
    '10kb' = '10kb'; '10240' = '10kb'
    '100kb' = '100kb'; '102400' = '100kb'
    '1mb' = '1mb'; '1048576' = '1mb'
}
if ($Sizes.Count -eq 0) { throw 'Sizes must contain at least one of 1kb, 10kb, 100kb, 1mb' }
$Sizes = [string[]]@(
    foreach ($item in $Sizes) {
        $key = ([string]$item).Trim().ToLowerInvariant()
        if (-not $sizeAliases.ContainsKey($key)) {
            throw "Invalid -Sizes value '$item'; expected 1kb, 10kb, 100kb, or 1mb (quote size labels in PowerShell)."
        }
        $sizeAliases[$key]
    }
)
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
