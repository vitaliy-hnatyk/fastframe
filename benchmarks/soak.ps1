# FastFrame v0.22: one-hour fixed-arrival soak, 3 frameworks, Windows.
param(
    [ValidateSet('fastframe','micronaut','laravel')][string[]] $Frameworks = @('fastframe','micronaut','laravel'),
    [ValidateSet('plain','json','route','echo')][string] $Case = 'json',
    [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane',
    [int] $LaravelWorkers = 4,
    [int] $Rate = 1000,
    [string] $Duration = '3600s',
    [string] $Warmup = '15s',
    [int] $Repeats = 1,
    [int] $Vus = 128,
    [int] $MaxVus = 512,
    [double] $SampleInterval = 10,
    [double] $GcInterval = 20,
    [double] $ProbeInterval = 15,
    [string] $Heap = '512m',
    [int] $Port = 18080,
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
    $argList = @('benchmarks/soak.py','--frameworks') + $Frameworks
    $argList += @('--case',$Case,'--laravel-server',$LaravelServer,
      '--laravel-workers',[string]$LaravelWorkers,'--rate',[string]$Rate,
      '--duration',$Duration,'--warmup',$Warmup,'--repeats',[string]$Repeats,
      '--vus',[string]$Vus,'--max-vus',[string]$MaxVus,
      '--sample-interval',[string]$SampleInterval,
      '--gc-interval',[string]$GcInterval,
      '--probe-interval',[string]$ProbeInterval,'--heap',$Heap,
      '--port',[string]$Port)
    Invoke-FastFramePython -Parameters $argList
    if ($LASTEXITCODE -ne 0) { throw "Soak benchmark failed: exit code $LASTEXITCODE" }
} finally { Pop-Location }
