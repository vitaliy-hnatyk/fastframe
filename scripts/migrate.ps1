# Explicitly apply versioned SQL migrations (Windows PowerShell, Java 21).
param([string]$Directory = 'demo-app/migrations', [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
if (-not $env:FF_DB_URL) { throw 'FF_DB_URL must be set to the target JDBC database URL' }
Push-Location $root
try {
    if (-not $SkipBuild) { Invoke-FastFrameMaven -Parameters @('-B','-ntp','-pl','demo-app','-am','package') }
    $jar = Join-Path $root 'demo-app/target/demo-app-0.21.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Missing $jar. Build first." }
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-cp' $jar 'dev.fastframe.migrations.MigrationCli' $Directory
    if ($LASTEXITCODE -ne 0) { throw "Migrations failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
