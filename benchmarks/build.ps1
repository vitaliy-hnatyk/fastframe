# Windows build for FastFrame and Micronaut benchmark applications.
param([switch] $SkipTests, [switch] $IncludeLaravel,
      [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $root 'scripts\windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    $first = @('-B', '-ntp', '-pl', 'benchmarks/fastframe-app', '-am', 'clean', 'package')
    $second = @('-B', '-ntp', '-f', 'benchmarks/micronaut-app/pom.xml', 'clean', 'package')
    if ($SkipTests) {
        $first += '-DskipTests'
        $second += '-DskipTests'
    }
    Invoke-FastFrameMaven -Parameters $first
    Invoke-FastFrameMaven -Parameters $second
    if ($IncludeLaravel) {
        $laravelArgs = @('benchmarks/setup_laravel.py')
        if ($LaravelServer -eq 'octane') { $laravelArgs += '--with-octane' }
        Invoke-FastFramePython -Parameters $laravelArgs
    }
    Write-Host 'Selected benchmark applications built.' 
} finally {
    Pop-Location
}
