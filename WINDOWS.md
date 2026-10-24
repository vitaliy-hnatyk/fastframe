# FastFrame v0.7.1 — Windows setup (Java 8 + Java 21)

These instructions are for **Windows 10/11**, PowerShell 5.1 or PowerShell 7, and the `fastframe-v0.7.1` folder containing `pom.xml`. **JDK 21 is required to build and run FastFrame.** Your other applications can continue using JDK 8.

## 1. Install prerequisites

In PowerShell (an Internet connection is needed the first time Maven downloads dependencies):

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

Install **Apache Maven 3.9+** and **Python 3.10+** if they are not already available (`mvn -version`, `python --version`). Add Maven's `bin` directory to PATH via the installer/system settings. A Maven Wrapper (`mvnw.cmd`) is also supported if you later add one to the project root.

To benchmark, install **Go** and `hey` (not required to run the demo or the HTTP preflight):

```powershell
winget install GoLang.Go
# Open a new PowerShell after Go installs, then:
go install github.com/rakyll/hey@latest
$env:Path = "$env:USERPROFILE\go\bin;$env:Path"
hey -h
```

This repository does not bundle Go, `hey`, Maven or JDK binaries. Maven 3.9+, Python 3.10+, and JDK 21 are required; Go is only needed to install `hey` from source. Do not run internet-fetched scripts as administrator.

## 2. Verify JDK 21 without uninstalling Java 8

From the project root:

```powershell
. .\scripts\windows-env.ps1
Use-FastFrameJava21
java -version
javac -version
mvn -version
```

The project first checks **`C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`**, then `JAVA_HOME` and other common JDK 21 installation folders, and prepends the selected JDK's `bin` directory to **the current PowerShell process only**. It does not change the Windows registry or persistent user/system environment variables. In a new terminal, `java -version` may still show Java 8; that is expected.

If the JDK is in a custom directory, set its location for the session:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\scripts\run-demo.ps1
```

Use your actual path, not the example directory.

## 3. Start the demo HTTP server

```powershell
.\scripts\run-demo.ps1
# Or a different port:
.\scripts\run-demo.ps1 -Port 8081
```

In another terminal:

```powershell
curl.exe http://127.0.0.1:8080/api/health
```

`Ctrl+C` stops the demo. To run an existing compiled jar, use `-SkipBuild`.

## 4. Build and test the benchmark apps

```powershell
.\benchmarks\build.ps1
.\benchmarks\preflight.ps1
```

The preflight checks `plain`, `json`, `route` and `echo` for both Java applications. It requires no `hey` and writes failure logs into their `target` directories. The benchmark app listens on localhost port **18080**.

## 5. Run benchmarks

```powershell
.\benchmarks\run.ps1 -SkipBuild -SkipPreflight -Duration 20s -Warmup 5s -Concurrency 64 -Repeats 3
# Test higher concurrency:
.\benchmarks\run.ps1 -SkipBuild -Concurrency 256 -Repeats 3
```

With no flags, `run.ps1` builds both applications, runs preflight, then benchmarks. Results are written to `benchmarks/results/<timestamp>/results.csv` and `results.json`, alongside server logs and `environment.json`. Windows memory is process working set (MiB), and `cpu_cores_avg` is average logical CPU core utilization, not system CPU percentage. Actual RPS depends on your PC and load generator; do not compare raw macOS and Windows numbers directly.

To summarize, use the actual path printed by the runner:

```powershell
python benchmarks/summarize.py benchmarks/results/20261024T123000Z/results.csv
```

## Troubleshooting

- **`running scripts is disabled`**: for this terminal only, run `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`; then rerun the script. Do not change the machine-wide policy unless your organization permits it.
- **`JDK 21 not found`**: check `C:\Program Files\Eclipse Adoptium` and set `$env:JAVA_HOME` to the installed JDK 21 folder; the project cannot compile on JDK 8.
- **`Maven 3.9+ is required`**: install Maven 3.9+ and add its `bin` directory to PATH (`mvn -version`).
- **`Missing hey.exe`**: run `go install github.com/rakyll/hey@latest` and add your Go `bin` directory to PATH, or download the Windows binary from the official GitHub repository.
- **Port 18080 already in use**: stop the old benchmark process. Use `-Port 18081` with `benchmarks\run.ps1` (it passes the port to preflight and the benchmark), or `python benchmarks/preflight.py --port 18081`.
- **Memory/CPU metrics absent**: the Windows APIs may be denied access to another user's processes. Run normal (non-admin) FastFrame and Python processes under the same account.
- **Different RPS from macOS**: expected across distinct operating systems, machines and background loads. Compare FastFrame and Micronaut within each host, with repeats and identical workloads.
- **Windows ARM64**: use the appropriate JDK build, Maven and Go/hey for your architecture. The code is not yet validated on ARM64 hardware.

This is still an experimental HTTP framework, not production-hardened software.
