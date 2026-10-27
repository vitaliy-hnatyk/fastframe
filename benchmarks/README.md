## Async I/O + JWT (fifth benchmark, FastFrame v0.22)

[ASYNC_JWT.md](ASYNC_JWT.md) describes real outbound HTTP waits (10/50/200 ms), HS256 token verification,
negative auth preflight, and CSV/Markdown/HTML reports for **FastFrame, Micronaut and Laravel Octane**.

```bash
python3 benchmarks/async_jwt.py --frameworks fastframe micronaut laravel \
  --laravel-server octane --cases async_10 async_50 async_200 jwt \
  --duration 30s --warmup 5s --concurrency 64 --repeats 3
```

This suite uses `hey` and a bundled asyncio upstream, **no Docker/k6 required**.

## PostgreSQL / Redis (FastFrame v0.22)

A fourth benchmark, `db_redis.py`, tests six real database/cache endpoints on all
three servers and reports RPS, p95/p99 and persisted side effects. Requires Docker
Desktop/Engine + Compose v2; see [DATABASE_REDIS.md](DATABASE_REDIS.md).

```bash
bash benchmarks/build.sh --with-laravel
python3 benchmarks/db_redis.py --frameworks fastframe micronaut laravel --duration 30s --warmup 5s --concurrency 64 --repeats 3
```

# FastFrame v0.22 — HTTP benchmark suite

This directory contains **three intentionally minimal standalone HTTP/1.1 servers** with corresponding endpoints:

| Method | Path | Workload | Expected response |
|---|---|---|---|
| GET | `/bench/plain` | Routing + text | `ok` |
| GET | `/bench/json` | Routing + map JSON serialization | `{"message":"ok","value":42}` (JSON object; key ordering ignored) |
| GET | `/bench/route/123` | Dynamic path parameter | `123` |
| POST | `/bench/echo` | JSON body binding + JSON serialization | `{"message":"hello"}` |

`fastframe-app/` compiles controllers via FastFrame's annotation processor. `micronaut-app/` compiles Micronaut controllers and Micronaut Serde with their own annotation processors. The Laravel fixture is a **separate Composer-installed Laravel 13 project** under `laravel-app/runtime/` (ignored by Git). The Micronaut project is a **separate Maven project** to keep the comparison application isolated from FastFrame's reactor. The implementations use their normal serialization mechanisms, so serialization costs can differ by design. This is **not** a zero-overhead network-transport benchmark.

## New in v0.22: concurrency curves and HTML reports

A separate [concurrency scaling benchmark](CONCURRENCY.md) compares FastFrame, Micronaut and Laravel Octane across **1–1,024 simultaneous clients**, measuring **30 seconds per level**. It produces `concurrency-results.csv`, `concurrency-summary.csv`, `report.md` and self-contained `report.html` with **RPS vs concurrency and p99 vs concurrency** charts for each of the same four endpoints. Incomplete/error runs never appear as successful comparison points. Run with `python3 benchmarks/concurrency.py --frameworks fastframe micronaut laravel`, or the PowerShell `concurrency.ps1` wrapper.

## New in v0.22: Target RPS / p99 (open-loop)

[Target RPS / p99 benchmark](TARGET_RPS.md) adds **Grafana k6** constant-arrival-rate
load, measuring **60 seconds at each target rate** (default 1k–30k RPS).
It runs the same three frameworks and four endpoints, and produces **actual vs
target throughput**, p95/p99, dropped iterations, HTTP errors, CSV/JSON and an
offline HTML report. Unlike `hey` fixed-concurrency tests, new requests are
scheduled independently of response time. Install k6 (`brew install k6` on
macOS); it is only required for this open-loop suite.

Quick check:

```bash
python3 benchmarks/target_rps.py --frameworks fastframe micronaut laravel \
  --cases plain --rates 100 500 --duration 3s --warmup 1s \
  --vus 32 --max-vus 64
```

Full run and Windows examples are in [TARGET_RPS.md](TARGET_RPS.md).

## New in v0.22: JSON Sizes (1 KiB — 1 MiB)

[JSON Size Benchmark](JSON_SIZES.md) adds four fully verified UTF-8 JSON response sizes
(1,024 / 10,240 / 102,400 / 1,048,576 bytes) on all three frameworks.
`json_sizes.py` measures RPS, response MiB/s, p95/p99, and error counts with
identical server-side serialization workloads; it produces recoverable CSV/JSON,
median summaries, and an offline HTML report with three charts. Preflight checks
exact response bytes before load testing, not just Content-Length.

```bash
python3 benchmarks/json_sizes.py --frameworks fastframe micronaut laravel \
  --sizes 1kb 10kb 100kb 1mb --duration 30s --warmup 5s \
  --concurrency 64 --repeats 3
```

## Requirements

- **Windows, Linux or macOS**, JDK 21+, Maven 3.9+, Python 3.10+
- [`hey` HTTP load generator](https://github.com/rakyll/hey) installed on `PATH` (e.g. `go install github.com/rakyll/hey@latest`)
- Maven Central access for the first build; Composer + PHP 8.3+ and Packagist access only if Laravel is selected
- Idle host; no other benchmarks running concurrently

### Windows quick setup

Use **PowerShell** (Windows PowerShell 5.1 or PowerShell 7), not Bash. Follow [../WINDOWS.md](../WINDOWS.md) for installing Java 21, Maven, Python and `hey`. In the project root:

```powershell
.\benchmarks\build.ps1
.\benchmarks\preflight.ps1
.\benchmarks\run.ps1 -SkipBuild -SkipPreflight -Concurrency 64 -Repeats 3
```

The benchmark runner detects Windows and uses `GetProcessMemoryInfo` / `GetProcessTimes` through the built-in Python `ctypes` module to sample the **working set** (MiB) and cumulative process CPU seconds. The WinAPI result, Linux RSS and macOS RSS use different platform accounting rules; compare only on the same machine and OS. If a process exits before sampling, a measurement can be null; do not treat missing readings as zero.

### Laravel 13 + PHP benchmark (macOS/Linux/Windows)

The default two-framework benchmark continues to work **without PHP or Composer**.
To include Laravel, install **PHP 8.3+** and **Composer 2**. For a fairer long-lived
application-server comparison, use **Laravel Octane + FrankenPHP**. Its workers keep
the Laravel application warm across requests. The exact Laravel and Octane versions
and Composer lockfile SHA-256 are recorded in `environment.json`. The basic `artisan serve` mode is
for smoke testing and **is not a production throughput baseline**.

```bash
# On macOS, if needed:
brew install php composer
php -v
composer --version

# From the FastFrame project root: build all three benchmarks and install Octane.
bash benchmarks/build.sh --with-laravel
python3 benchmarks/preflight.py --frameworks fastframe micronaut laravel
python3 benchmarks/run.py --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --duration 30s --warmup 10s --concurrency 64 --repeats 5
```

A shorter **Laravel-only development-server** test requires no Octane binary:

```bash
python3 benchmarks/setup_laravel.py
python3 benchmarks/preflight.py --frameworks laravel --laravel-server artisan
python3 benchmarks/run.py --frameworks laravel --laravel-server artisan \
  --duration 5s --warmup 2s --concurrency 16 --repeats 1
```

PowerShell:

```powershell
.\benchmarks\build.ps1 -IncludeLaravel -LaravelServer octane
.\benchmarks\preflight.ps1 -Frameworks fastframe,micronaut,laravel
.\benchmarks\run.ps1 -SkipBuild -SkipPreflight -Frameworks fastframe,micronaut,laravel `
    -LaravelServer octane -LaravelWorkers 4 -Concurrency 64 -Repeats 5
```

The installer uses `composer create-project laravel/laravel:^13.0` and applies the
tracked `laravel-app/routes/bench.php` and `laravel-app/bootstrap/app.php`. The generated
Laravel project and its `vendor/` are excluded from Git and ZIP. The routes use
real Laravel response helpers and JSON body parsing, without the default `web`
middleware group (which would add cookies, session and CSRF to a stateless API test).
An initial setup can download the FrankenPHP binary. See
[official Laravel Octane documentation](https://laravel.com/docs/13.x/octane).

**Resource metrics caveat:** `php artisan serve` and Octane can launch worker child
processes. This runner reports `rss_peak_mb` and `cpu_cores_avg` as blank (`null`)
for Laravel instead of mistakenly counting only the PHP launcher process. Laravel's
**HTTP RPS and latency are measured normally**. The Java benchmarks keep their existing
single-process CPU/RSS measurements. Also, PHP has different GC and memory models,
so matching `-Xmx` is not meaningful; worker count, runtime mode and host hardware
are recorded separately. For scientific comparisons, isolate the load generator on
a separate machine and run each configuration repeatedly. `hey -o csv` incurs some
client overhead and may cap all three results at a similar throughput.

### macOS Java/Maven/hey setup

```bash
brew install openjdk@21 maven hey
# Ensure Java 21 is on PATH/JAVA_HOME as appropriate for your Homebrew installation.
java -version
mvn -version
hey -h
```

RSS/CPU collection uses WinAPI on Windows, `/proc` on Linux, and BSD `ps` on macOS. The
`ps` backend samples RSS every 500 ms rather than the Linux backend's 100 ms
to reduce benchmarking overhead. Peak RSS is therefore a *sampled estimate*
and may miss shorter peaks. The script records the selected backend in
`environment.json`. No administrator privileges or third-party Python monitoring packages
are required. Run all comparisons on **the same OS and machine**; do not compare raw
throughput or RAM numbers across operating systems as if they were identical.

FastFrame pins Netty 4.2.19.Final. Micronaut uses Platform BOM 4.10.21 / Core 4.10.31, including Netty 4.2.19.Final. Micronaut Serde is explicitly pinned to 2.16.2. Both applications use Java NIO transport (Micronaut native transport disabled), G1 and an identical max heap (`-Xms256m -Xmx256m` by default).

## Build

```bash
bash benchmarks/build.sh
# On Windows instead: .\benchmarks\build.ps1
# Or build individually:
mvn -B -ntp -pl benchmarks/fastframe-app -am clean package
mvn -B -ntp -f benchmarks/micronaut-app/pom.xml clean package
```

### Micronaut compiler error: `NullMarked`

If Maven reports `NoClassDefFoundError: io/micronaut/core/annotation/NullMarked`, the Maven Compiler Plugin's isolated annotation processor classpath is missing the compatible `micronaut-core`. This build config now explicitly adds `micronaut-core:4.10.31` to `annotationProcessorPaths` alongside `micronaut-inject-java:4.10.31`, and enables dependency management for transitive processor dependencies. The Platform BOM `4.10.21` intentionally corresponds to Core `4.10.31`; do **not** set both to `4.10.21`.

After applying the patch, re-run:

```bash
mvn -U -B -ntp -f benchmarks/micronaut-app/pom.xml clean package
python3 benchmarks/preflight.py
```

The exception stack traces printed by `NettyRequestHandlerTest` and `NettyLimitsTest` are expected by tests that verify internal error messages are not exposed; check their test summaries rather than interpreting these warning logs as test failures. Maven Shade warnings about duplicate `META-INF/MANIFEST.MF` and Netty module metadata are separate from the Micronaut compiler error.

## Test the built applications before benchmarking

```bash
python3 benchmarks/preflight.py
```

This runs each server in isolation, verifies **all four** HTTP endpoints and bodies, then shuts it down.
The preflight/runner uses `--port 18080` as the preferred port. If there is no
active listener but TCP `TIME_WAIT` prevents rebinding (macOS may report
`[Errno 48] Address already in use`), it automatically selects an ephemeral
loopback port and logs the actual port; `results.csv` records it in `port`.
An *active listening server* on the preferred port remains a hard error, to
prevent accidentally benchmarking a stale process. Diagnose that with:

```bash
lsof -nP -iTCP:18080 -sTCP:LISTEN
# Stop only the known benchmark server by PID, if present:
# kill <PID>
```
 GitHub Actions executes this after compiling both apps. Logs from a failed launch are left in each `target/` directory.

## Compare

```bash
python3 benchmarks/run.py --duration 20s --warmup 5s --concurrency 64 --repeats 3
```

The runner automatically verifies the HTTP status, media type, and decoded body semantics for each endpoint **before load**. It runs the servers **sequentially, on the same port**, reversing the framework order on alternate repetitions. Each process is started using the same Java heap settings. For every case it collects raw `hey -o csv` request samples, RPS (completed requests / observed wall duration), p50/p95/p99 in milliseconds, HTTP errors, peak process RSS, and mean process CPU cores during the load for Java servers (Laravel process-tree resource metrics are unavailable and recorded as null). It also records the command, OS, CPU count, and Java version.

Output appears in timestamped directories under `benchmarks/results/`, which Git ignores:

- `results.csv` / `results.json`: numeric results by run and case
- `raw-*.csv`: individual HTTP samples
- `warmup-*.csv`: warmup samples (not counted)
- `*-run-*.log`: server logs
- `environment.json`: configuration and host metadata

Aggregate completed runs:

```bash
python3 benchmarks/summarize.py benchmarks/results/<timestamp>/results.csv
```

For a quick local smoke test, reduce the duration and repeats; short runs **are not statistically stable**:

```bash
python3 benchmarks/run.py --duration 3s --warmup 2s --repeats 1 --cases plain route
```

## How to interpret results

- Treat `errors > 0` as a failed comparison, not extra throughput. `hey` only records completed attempts; check logs for failures and connection errors.
- Fixed concurrency (`-c`) benchmarks measure saturation and can hide coordinated omission. For SLA latency, repeat tests under fixed offered RPS with an open-loop generator such as `wrk2` or `vegeta`, including error and timeout accounting.
- `-Xmx` controls **heap**, not total RSS; native buffers, loaded classes, threads and code cache also count. Peak RSS is sampled at ~100 ms on Linux and ~500 ms on Windows/macOS, not an instantaneous maximum.
- `cpu_cores_avg=1.0` approximates one fully used logical CPU over the measured interval; it is not a percentage of machine capacity.
- On Windows, process working set comes from `GetProcessMemoryInfo` and CPU time from `GetProcessTimes`, both sampled.
- On macOS, RSS comes from `ps -o rss=` (KiB) and CPU time from `ps -o time=`. Both are sampled, and macOS RSS measurements may differ from Linux due to OS memory-accounting differences; compare frameworks within one host/OS only.
- The generator on the same machine can become CPU-limited. Serious comparisons should use a separate load-generator host on a controlled network and fixed CPU affinity/host tuning.
- JSON implementations differ between all three frameworks. Tests compare response semantics, not allocation patterns or complete framework feature parity.
- v0.21 adds a Windows JFR profiling script and allocation diagnostic. These are diagnostics, not replacements for end-to-end testing or statistically sound microbenchmarking. See `../PERFORMANCE.md`.
- Do not claim FastFrame is faster unless repeated, error-free measurements support it. Publish individual run data and all runtime configurations.

## Local smoke tests without Maven

```bash
bash scripts/benchmark-smoke.sh
```

This uses `javac` to generate and execute the FastFrame benchmark router and Python tests for the CSV summarizer. It does **not** test the Micronaut executable or Netty integration; those need the Maven build and actual HTTP preflight.

## v0.7 optimization validation

The v0.7 FastFrame benchmark server uses the same routes and benchmark API as v0.6. To quantify the effect, build both branches independently and run the same commands on an otherwise idle machine, with comparable warm-up, JVM heap, and concurrency. Compare medians over repeated runs, error counts, p99, peak RSS, and CPU. Do not benchmark with both servers loaded simultaneously.

```bash
bash benchmarks/build.sh
python3 benchmarks/preflight.py
python3 benchmarks/run.py --duration 30s --warmup 10s --concurrency 64 --repeats 3
python3 benchmarks/run.py --duration 30s --warmup 10s --concurrency 256 --repeats 3
```

Do not interpret a small change in RPS as a framework speedup unless the effect exceeds run-to-run noise. The `hey` client can itself become the bottleneck at high concurrency.

## v0.21 — Windows JFR and cross-version comparison

`benchmarks/run.py` reads the FastFrame and Micronaut Maven POM files to resolve the correct artifact filenames. Build with `benchmarks/build.ps1` (Windows) or `benchmarks/build.sh` (Linux/macOS) first.

```powershell
.\benchmarks\profile.ps1 -Framework fastframe -Case json -Concurrency 64 -Duration 30s
python benchmarks/compare_versions.py --baseline C:\benchmarks\v020\results.csv --candidate C:\benchmarks\v021\results.csv
```

No artificial RPS numbers are produced by these scripts.

## v0.22 — 60-minute soak: RSS, CPU, Java heap and GC, errors

The **sixth** benchmark suite uses fixed-arrival k6 with 60-minute measurements per framework and
samples full process-tree RSS/CPU (including Laravel Octane workers), Java heap and JVM GC (`jstat`),
and independent HTTP probe errors. HTML, CSV, JSON and Markdown reports are saved automatically.

See [SOAK.md](SOAK.md) for installation, limitations, and quick/full commands.

```bash
python3 -m pip install psutil
python3 benchmarks/soak.py --frameworks fastframe micronaut laravel --case json \
  --rate 1000 --duration 3600s --warmup 15s --sample-interval 10
```
