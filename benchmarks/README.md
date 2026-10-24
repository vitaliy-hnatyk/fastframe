# FastFrame v0.7.1 — HTTP benchmark suite

This directory contains **two intentionally minimal standalone HTTP/1.1 servers** with corresponding endpoints:

| Method | Path | Workload | Expected response |
|---|---|---|---|
| GET | `/bench/plain` | Routing + text | `ok` |
| GET | `/bench/json` | Routing + map JSON serialization | `{"message":"ok","value":42}` (JSON object; key ordering ignored) |
| GET | `/bench/route/123` | Dynamic path parameter | `123` |
| POST | `/bench/echo` | JSON body binding + JSON serialization | `{"message":"hello"}` |

`fastframe-app/` compiles controllers via FastFrame's annotation processor. `micronaut-app/` compiles Micronaut controllers and Micronaut Serde with their own annotation processors. The Micronaut project is a **separate Maven project** to keep the comparison application isolated from FastFrame's reactor. The implementations use their normal serialization mechanisms, so serialization costs can differ by design. This is **not** a zero-overhead network-transport benchmark.

## Requirements

- **Windows, Linux or macOS**, JDK 21+, Maven 3.9+, Python 3.10+
- [`hey` HTTP load generator](https://github.com/rakyll/hey) installed on `PATH` (e.g. `go install github.com/rakyll/hey@latest`)
- Maven Central access for the first build
- Idle host; no other benchmarks running concurrently

### Windows quick setup

Use **PowerShell** (Windows PowerShell 5.1 or PowerShell 7), not Bash. Follow [../WINDOWS.md](../WINDOWS.md) for installing Java 21, Maven, Python and `hey`. In the project root:

```powershell
.\benchmarks\build.ps1
.\benchmarks\preflight.ps1
.\benchmarks\run.ps1 -SkipBuild -SkipPreflight -Concurrency 64 -Repeats 3
```

The benchmark runner detects Windows and uses `GetProcessMemoryInfo` / `GetProcessTimes` through the built-in Python `ctypes` module to sample the **working set** (MiB) and cumulative process CPU seconds. The WinAPI result, Linux RSS and macOS RSS use different platform accounting rules; compare only on the same machine and OS. If a process exits before sampling, a measurement can be null; do not treat missing readings as zero.

### macOS quick setup

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

This runs each server in isolation, verifies **all four** HTTP endpoints and bodies, then shuts it down. GitHub Actions executes this after compiling both apps. Logs from a failed launch are left in each `target/` directory.

## Compare

```bash
python3 benchmarks/run.py --duration 20s --warmup 5s --concurrency 64 --repeats 3
```

The runner automatically verifies the HTTP status, media type, and decoded body semantics for each endpoint **before load**. It runs the servers **sequentially, on the same port**, alternating which one goes first by repetition. Each process is started using the same Java heap settings. For every case it collects raw `hey -o csv` request samples, RPS (completed requests / observed wall duration), p50/p95/p99 in milliseconds, HTTP errors, peak process RSS, and mean process CPU cores during the load. It also records the command, OS, CPU count, and Java version.

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
- JSON endpoints are not identical serialization implementations. Tests compare semantics, not allocation patterns or feature-completeness of the two frameworks.
- v0.7.1 does **not** implement JFR / JMH allocation profiling; profiling is a separate next step and must target comparable application workloads.
- Do not claim FastFrame is faster unless repeated, error-free measurements support it. Publish individual run data and both JVM/application configurations.

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
