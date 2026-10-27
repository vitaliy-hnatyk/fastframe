# Validate Windows JDK 21 config module and the CLI. No secrets are printed.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
try {
    Invoke-FastFrameMaven -Parameters @('-B', '-ntp', '-pl', 'framework-config,framework-cli', '-am', 'clean', 'verify')
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("fastframe-config-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    try {
        $src = Join-Path $root 'scripts\ConfigSmoke.java'
        $jar = Join-Path $root 'framework-config\target\framework-config-0.22.0-SNAPSHOT.jar'
        & (Join-Path $env:JAVA_HOME 'bin\javac.exe') '-cp' $jar '-d' $tmp $src
        if ($LASTEXITCODE -ne 0) { throw 'Config smoke compilation failed' }
        & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-cp' "$jar;$tmp" 'ConfigSmoke'
        if ($LASTEXITCODE -ne 0) { throw 'Config smoke failed' }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force
    }
    Write-Host 'FastFrame v0.16 Windows config smoke: PASS'
} finally {
    Pop-Location
}
