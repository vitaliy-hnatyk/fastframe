## v0.21 performance validation

Use `scripts/json-allocation.ps1` to measure allocations and `benchmarks/profile.ps1` for JFR recordings of FastFrame or Micronaut. To collect error-free HTTP RPS/p99/RSS measurements use `benchmarks/run.ps1`. Full commands and interpretation: [PERFORMANCE.md](PERFORMANCE.md). Existing JDK 21 selector still prefers `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` while leaving Java 8 as the global default.

## FastFrame v0.19 — Windows operations smoke

The existing `scripts/windows-env.ps1` selects Java 21 (including `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` when present), without changing system Java 8.

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\observability-smoke.ps1
.\scripts\run-demo.ps1
```

In a second terminal, use `curl.exe -i http://127.0.0.1:8080/_health/ready`. Refer to [OBSERVABILITY.md](OBSERVABILITY.md) to enable metrics only on localhost.

---

## FastFrame v0.19 tests

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\testing-smoke.ps1
```

See [TESTING.md](TESTING.md). No global Java 8 changes.

---

## FastFrame v0.16 — Windows configuration and CLI

Run `.\scripts\config-smoke.ps1` and `.\scripts\cli-smoke.ps1` before launching the demo. Use `.\scripts\ff.ps1 make:service Invoice`, `make:request CreateInvoice`, `make:middleware Audit` or `make:job SendInvoice`. Java 21 is selected per PowerShell process; global Java 8 stays intact.

**XAMPP security:** because your checkout is inside `C:\xampp\www`, store the private environment file outside that directory:

```powershell
New-Item -ItemType Directory -Force 'C:\fastframe-private' | Out-Null
Copy-Item .\.env.example 'C:\fastframe-private\.env'
$env:FF_ENV_FILE = 'C:\fastframe-private\.env'
.\scripts\run-demo.ps1
```

See [CONFIG.md](CONFIG.md) for examples and override order. Do not expose the development server publicly or commit credentials.

---

## FastFrame v0.15 CLI for Windows

Use `scripts/ff.ps1` to generate controllers, models, migrations and list annotated routes. No need to uninstall Java 8. The script selects your installed Temurin JDK 21. See [CLI.md](CLI.md) for command examples and limitations.

# FastFrame v0.14 — Windows notes

Run the storage and HTTP client test suite on your existing Java 21 installation:

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\storage-smoke.ps1
.\scripts\run-demo.ps1
```

The JDK selector prefers `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` and does not change the global Java 8 installation. See [STORAGE_HTTP.md](STORAGE_HTTP.md) for opt-in localhost binary upload demo, S3 configuration and HTTP client examples.

---

## v0.10 Windows ORM and migrations

Java 8 stays installed; `scripts/run-demo.ps1`, `scripts/migrate.ps1` and `scripts/orm-smoke.ps1` select the configured Temurin JDK 21 per session. Refer to [ORM_MIGRATIONS.md](ORM_MIGRATIONS.md).

```powershell
$env:FF_DB_URL='jdbc:h2:mem:fastframe;DB_CLOSE_DELAY=-1'
.\scripts\run-demo.ps1
# In a second terminal:
curl.exe http://localhost:8080/api/db/users
```

For DB migrations on a persistent JDBC database, configure `FF_DB_URL` and run `.\scripts\migrate.ps1`. Do not automatically migrate production databases during HTTP startup.

# Database demo (v0.9)

```powershell
$env:FF_DB_URL = 'jdbc:h2:mem:fastframe;DB_CLOSE_DELAY=-1'
.\scripts\run-demo.ps1
# In another PowerShell:
curl.exe http://localhost:8080/api/db/users
```

The demo uses JDK 21 in the current PowerShell process. Your machine-wide JDK 8 remains unchanged. See [DATABASE.md](DATABASE.md).

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


## FastFrame v0.13: Queues

Run `.\scripts\queue-smoke.ps1` for local tests. For Redis jobs configure `FF_REDIS_URI`, then start `.\scripts\run-queue.ps1 -Command worker` and `.\scripts\run-queue.ps1 -Command scheduler` in separate terminals. Full instructions: `QUEUE_SCHEDULER.md`.


## v0.17 bootstrap / events

Run `.\scripts\bootstrap-smoke.ps1`, and see [BOOTSTRAP_EVENTS.md](BOOTSTRAP_EVENTS.md).


## v0.20 HTTP security (Windows)

`./scripts/security-smoke.ps1` runs the Maven tests for `framework-security` and `framework-netty` with JDK 21.

Example before starting demo:

```powershell
$env:FF_CORS_ALLOWED_ORIGINS = 'http://localhost:3000'
$env:FF_RATE_LIMIT_PER_MINUTE = '120'
$env:FF_HSTS = 'false'
./scripts/run-demo.ps1
```

For live Windows deployments, move the checkout out of `C:\xampp\www` and see `PRODUCTION.md`. The limiter keys on Netty socket IP (likely Apache's IP behind XAMPP), not `X-Forwarded-For`.


### Benchmark Python tests on Windows CI (v0.22 hotfix)

Benchmark reports are encoded in UTF-8. Windows PowerShell users can run:

```powershell
python -m unittest discover -s benchmarks/tests -v
```

The POSIX `ps` integration tests are skipped on Windows because RSS/CPU monitoring uses native Windows APIs there. An occupied/restricted TCP port is rejected rather than silently reused; inspect it with `Get-NetTCPConnection -LocalPort 18080` before starting a load test.
