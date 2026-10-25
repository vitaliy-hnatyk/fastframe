# Maven-free JDBC Query Builder SQL-generation smoke test (Windows / PowerShell).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
$temp = Join-Path $env:TEMP ("ff-db-smoke-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null
try {
    $src = @(Get-ChildItem -Path (Join-Path $root 'framework-database\src\main\java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    & $javac '--release' '21' '-d' $temp @src
    if ($LASTEXITCODE -ne 0) { throw 'Database Java compilation failed' }
    & $javac '--release' '21' '-cp' $temp '-d' $temp (Join-Path $root 'scripts\DatabaseSmoke.java')
    if ($LASTEXITCODE -ne 0) { throw 'DatabaseSmoke compilation failed' }
    & $java '-cp' $temp 'DatabaseSmoke'
    if ($LASTEXITCODE -ne 0) { throw 'Database smoke test failed' }
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
