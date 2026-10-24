# Shared Windows helpers; dot-source from FastFrame's PowerShell scripts.
# Deliberately changes JAVA_HOME/PATH only in this PowerShell process (and children).
$script:FastFrameWindowsRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Use-FastFrameJava21 {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    foreach ($folder in @(
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Microsoft",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )) {
        if (Test-Path -LiteralPath $folder -PathType Container) {
            Get-ChildItem -LiteralPath $folder -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '^jdk-21([.\-+_]|$)' } |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }

    $pathJavac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($pathJavac) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $pathJavac.Source)))
    }

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $javac = Join-Path $candidate 'bin\javac.exe'
        $java = Join-Path $candidate 'bin\java.exe'
        if (-not ((Test-Path -LiteralPath $javac -PathType Leaf) -and
                   (Test-Path -LiteralPath $java -PathType Leaf))) { continue }
        # javac -version prints to stdout and avoids the special stderr treatment
        # that Windows PowerShell 5.1 applies to `java -version`.
        $version = (& $javac -version 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $version -match '^javac 21(\.|\s|$)') {
            $env:JAVA_HOME = (Resolve-Path -LiteralPath $candidate).Path
            $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
            Write-Host "Using Java 21: $env:JAVA_HOME"
            return
        }
    }
    throw 'JDK 21 not found. Install: winget install EclipseAdoptium.Temurin.21.JDK; or set JAVA_HOME to your JDK 21 directory.'
}

function Get-FastFrameMaven {
    $root = $script:FastFrameWindowsRoot
    $wrapper = Join-Path $root 'mvnw.cmd'
    if (Test-Path -LiteralPath $wrapper -PathType Leaf) { return $wrapper }
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($maven) { return $maven.Source }
    $maven = Get-Command mvn -ErrorAction SilentlyContinue
    if ($maven) { return $maven.Source }
    throw 'Maven 3.9+ is required. Install Maven, add its bin directory to PATH, then run mvn -version.'
}

function Invoke-FastFrameMaven {
    param([Parameter(Mandatory=$true)][string[]] $Parameters)
    $mvn = Get-FastFrameMaven
    & $mvn @Parameters
    if ($LASTEXITCODE -ne 0) { throw "Maven command failed (exit code $LASTEXITCODE)" }
}

function Invoke-FastFramePython {
    param([Parameter(Mandatory=$true)][string[]] $Parameters)
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($python) {
        & $python.Source @Parameters
    } else {
        $launcher = Get-Command py.exe -ErrorAction SilentlyContinue
        if (-not $launcher) { throw 'Python 3.10+ is required. Install Python and add it to PATH.' }
        & $launcher.Source '-3' @Parameters
    }
    if ($LASTEXITCODE -ne 0) { throw "Python command failed (exit code $LASTEXITCODE)" }
}
