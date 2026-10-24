# Windows benchmark runner: builds, checks endpoints, then measures FastFrame vs Micronaut.
param(
    [ValidateRange(1,4096)][int] $Concurrency = 64,
    [ValidateRange(1,100)][int] $Repeats = 3,
    [string] $Duration = '20s',
    [string] $Warmup = '5s',
    [string] $Heap = '256m',
    [ValidateRange(1024,65535)][int] $Port = 18080,
    [ValidateSet('plain','json','route','echo')][string[]] $Cases = @('plain','json','route','echo'),
    [string] $Output = '',
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
        & (Join-Path $PSScriptRoot 'build.ps1')
    }
    if (-not $SkipPreflight) {
        Invoke-FastFramePython -Parameters @('benchmarks/preflight.py', '--port', [string]$Port)
    }
    if (-not (Get-Command hey.exe -ErrorAction SilentlyContinue)) {
        throw 'Missing hey.exe. Install Go, run: go install github.com/rakyll/hey@latest; add $env:USERPROFILE\go\bin to PATH.'
    }
    $params = @('benchmarks/run.py', '--duration', $Duration, '--warmup', $Warmup,
                '--concurrency', [string]$Concurrency, '--repeats', [string]$Repeats,
                '--heap', $Heap, '--port', [string]$Port, '--cases') + $Cases
    if ($Output) { $params += @('--output', $Output) }
    Invoke-FastFramePython -Parameters $params
} finally {
    Pop-Location
}
