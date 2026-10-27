# FastFrame Artisan-style CLI (PowerShell 5.1+/7). Does not modify system Java 8.
param(
    [Parameter(Mandatory=$true, Position=0)][string] $Command,
    [Parameter(Position=1)][string] $Name = '',
    [string] $Package,
    [switch] $SkipBuild
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    if (-not $SkipBuild) {
        Invoke-FastFrameMaven -Parameters @('-B', '-ntp', '-pl', 'framework-cli', '-am', '-DskipTests', 'package')
        if ($Command -eq 'route:list') {
            Invoke-FastFrameMaven -Parameters @('-B', '-ntp', '-pl', 'demo-app', '-am', '-DskipTests', 'clean', 'compile')
        }
    }
    $jar = Join-Path $root 'framework-cli\target\framework-cli-0.22.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw "Missing CLI jar: $jar. Run without -SkipBuild first."
    }
    $arguments = @('-jar', $jar, $Command)
    if ($Name) { $arguments += $Name }
    if ($Package) { $arguments += @('--package', $Package) }
    $arguments += @('--root', $root)
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') @arguments
    if ($LASTEXITCODE -ne 0) { throw "FastFrame CLI failed (exit code $LASTEXITCODE)" }
} finally {
    Pop-Location
}
