$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
$tmp = Join-Path $env:TEMP ("ff-orm-smoke-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
Push-Location $root
try {
    $src = @('framework-core','framework-database','framework-orm','framework-migrations') | ForEach-Object {
        Get-ChildItem -Path "$_/src/main/java" -Filter '*.java' -Recurse | ForEach-Object FullName
    }
    & $javac '--release' '21' '-d' $tmp @src
    if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed' }
    & $javac '--release' '21' '-cp' $tmp '-proc:none' '-d' $tmp 'framework-processor/src/main/java/dev/fastframe/processor/FastFrameModelProcessor.java'
    if ($LASTEXITCODE -ne 0) { throw 'Model processor compilation failed' }
    & $javac '--release' '21' '-cp' $tmp '-processor' 'dev.fastframe.processor.FastFrameModelProcessor' '-d' $tmp 'scripts/OrmSmokeUser.java'
    if ($LASTEXITCODE -ne 0) { throw 'Model mapper generation failed' }
    & $javac '--release' '21' '-cp' $tmp '-d' $tmp 'scripts/OrmSmokeMain.java' 'scripts/MigrationSmoke.java'
    if ($LASTEXITCODE -ne 0) { throw 'Smoke compilation failed' }
    & $java '-cp' $tmp 'OrmSmokeMain'
    if ($LASTEXITCODE -ne 0) { throw 'ORM smoke failed' }
    & $java '-cp' $tmp 'MigrationSmoke'
    if ($LASTEXITCODE -ne 0) { throw 'Migration smoke failed' }
} finally {
    Pop-Location
    Remove-Item -Recurse -Force -LiteralPath $tmp -ErrorAction SilentlyContinue
}
