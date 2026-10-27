param([ValidateRange(1024,65535)][int] $Port = 18080,
      [ValidateSet('fastframe','micronaut','laravel')][string[]] $Frameworks = @('fastframe','micronaut'),
      [ValidateSet('octane','artisan')][string] $LaravelServer = 'octane',
      [ValidateRange(1,512)][int] $LaravelWorkers = 4)
# Check compiled FastFrame and Micronaut HTTP endpoints on Windows (no hey required).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $root 'scripts\windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFramePython -Parameters (@('benchmarks/preflight.py', '--port', [string] $Port,
        '--laravel-server', $LaravelServer, '--laravel-workers', [string] $LaravelWorkers,
        '--frameworks') + $Frameworks)
} finally {
    Pop-Location
}
