# Windows CLI smoke: creates files in a disposable temp directory, never in your repo.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'windows-env.ps1')
Use-FastFrameJava21
Push-Location $root
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ("fastframe-cli-smoke-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null
try {
    Invoke-FastFrameMaven -Parameters @('-B', '-ntp', '-pl', 'framework-cli', '-am', 'clean', 'verify')
    $jar = Join-Path $root 'framework-cli\target\framework-cli-0.22.0-SNAPSHOT.jar'
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    & $java '-jar' $jar 'make:controller' 'Post' '--root' $temp
    if ($LASTEXITCODE -ne 0) { throw 'Controller generation failed' }
    & $java '-jar' $jar 'make:model' 'Category' '--root' $temp
    if ($LASTEXITCODE -ne 0) { throw 'Model generation failed' }
    & $java '-jar' $jar 'make:migration' 'create_posts' '--root' $temp
    if ($LASTEXITCODE -ne 0) { throw 'Migration generation failed' }
    foreach ($commandName in @('make:service', 'make:request', 'make:middleware', 'make:job')) {
        & $java '-jar' $jar $commandName 'Sample' '--root' $temp
        if ($LASTEXITCODE -ne 0) { throw "Generator failed: $commandName" }
    }
    foreach ($relative in @(
        'demo-app\src\main\java\dev\fastframe\demo\controllers\PostController.java',
        'demo-app\src\main\java\dev\fastframe\demo\models\Category.java',
        'demo-app\migrations\V001__create_posts.sql',
        'demo-app\src\main\java\dev\fastframe\demo\services\SampleService.java',
        'demo-app\src\main\java\dev\fastframe\demo\dto\SampleRequest.java',
        'demo-app\src\main\java\dev\fastframe\demo\middleware\SampleMiddleware.java',
        'demo-app\src\main\java\dev\fastframe\demo\jobs\SampleJob.java'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $temp $relative) -PathType Leaf)) {
            throw "Generated file missing: $relative"
        }
    }
    Write-Host 'FastFrame Windows CLI generation smoke: PASS'
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
}
