# FastFrame v0.22 — HTTP Concurrency Scaling Benchmark

Run **FastFrame, Micronaut and Laravel Octane + FrankenPHP** with the same four HTTP/1.1 endpoints and concurrency levels, one server at a time, from a controlled benchmark. This is a **benchmark implementation**, not a performance claim: no HTTP results are bundled or fabricated.

## Standard profile

| Setting | Default |
|---|---|
| Frameworks | FastFrame, Micronaut, Laravel Octane (FrankenPHP) |
| Endpoints | `plain`, `json`, `route`, `echo` (same expected body as existing benchmark) |
| Client concurrency | **1, 8, 16, 32, 64, 128, 256, 512, 1,024** |
| Measured duration | **30 seconds PER concurrency level, endpoint, framework and repeat** |
| Warmup | 5 seconds **before each measured level** |
| Repetitions | 1 by default; recommended 3–5 for stronger comparisons |
| HTTP driver | `hey -z 30s -c <level> -o csv` |
| Transport / Java heap | Existing FastFrame/Micronaut harness, 256 MiB heap by default |
| PHP runtime | Laravel Octane + FrankenPHP, 4 workers by default |

**Runtime:** 3 frameworks × 4 endpoints × 9 levels × (30s + 5s) = **about 63 minutes of load/warmup**, plus startup and report generation. Three repetitions take about 3h 9min plus startup. With only `--cases json`, one repeat uses about 15m 45s plus startup. Do not interpret missing or aborted measurements as zero RPS.

## macOS quick start

Requirements: JDK 21, Maven, Python 3.10+, `hey`, PHP 8.3+, Composer 2 and Octane/FrankenPHP (for Laravel). Laravel is downloaded at setup time, not committed. First build can require network access.

```bash
cd ~/Downloads/netty-laravel-starter
brew install php composer hey
# Check macOS open-file limit: it should accommodate 1024 clients plus overhead.
ulimit -n
# If your account allows it and the limit is lower, use: ulimit -n 4096
bash benchmarks/build.sh --with-laravel
python3 benchmarks/preflight.py --frameworks fastframe micronaut laravel

# Full run: 4 endpoints, 9 concurrency levels, 30s each.
python3 benchmarks/concurrency.py \
  --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --duration 30s --warmup 5s --repeats 1
```

### Fast validation (not a publishable benchmark)

```bash
python3 benchmarks/concurrency.py \
  --frameworks fastframe micronaut laravel \
  --cases plain --levels 1 16 64 \
  --duration 2s --warmup 1s --repeats 1
```

### JSON scaling with repeat medians

```bash
python3 benchmarks/concurrency.py --cases json \
  --frameworks fastframe micronaut laravel \
  --levels 1 8 16 32 64 128 256 512 1024 \
  --duration 30s --warmup 5s --repeats 3
```

Linux/macOS shell wrapper: `bash benchmarks/concurrency.sh --cases json --repeats 3`

## Windows PowerShell

```powershell
.\benchmarks\build.ps1 -IncludeLaravel -LaravelServer octane
.\benchmarks\preflight.ps1 -Frameworks fastframe,micronaut,laravel
.\benchmarks\concurrency.ps1 -SkipBuild -SkipPreflight `
    -Frameworks fastframe,micronaut,laravel -LaravelServer octane `
    -Levels 1,8,16,32,64,128,256,512,1024 -Duration 30s -Warmup 5s -Repeats 1
```

The wrapper selects Java 21 using the project's `windows-env.ps1`. `-SkipBuild` and `-SkipPreflight` are suitable only after you successfully built and verified all three servers.

## Output and visualization

Each run writes `benchmarks/results/concurrency-<timestamp>/`:

| File | Meaning |
|---|---|
| `concurrency-results.csv` | **Measured individual repetitions**: framework, case, level, RPS, latency, errors, port, Java RSS/CPU |
| `concurrency-results.json` | Raw numerical measurements in JSON |
| `concurrency-summary.csv` / `.json` | Medians and validity flags per `(framework, case, concurrency)` |
| `report.html` | **Offline, self-contained line charts**: throughput vs concurrency and p99 latency vs concurrency, per endpoint |
| `report.md` | Comparison tables and FastFrame/competitor RPS ratios (only valid groups) |
| `environment.json` | Versions, host, PHP/Java settings, framework order inputs, worker count, hey help |
| `<framework>-run-<repeat>.log` | Server diagnostics |
| `raw-*.csv` | Individual HTTP samples only when `--keep-raw` is selected |

CSV is checkpointed **after every completed concurrency level**, even if a later level fails. Server subprocess trees are terminated in `finally`. The preferred port is automatically changed if TCP `TIME_WAIT` prevents rebinding and no active listener exists; an **active listener blocks the run** to avoid benchmarking a stale server.

Rebuild reports from existing data (no servers needed):

```bash
python3 benchmarks/concurrency_report.py benchmarks/results/concurrency-YYYYMMDDTHHMMSSffffffZ
open benchmarks/results/concurrency-YYYYMMDDTHHMMSSffffffZ/report.html  # macOS
```

The report includes ONLY **complete zero-error groups** in plots and comparisons. For `--repeats 3`, only 3 distinct successful runs per group are marked valid. Invalid and incomplete measurements remain visible in CSV and tables. Use `--keep-raw` to preserve the sometimes very large per-request samples; without it, these intermediate files are discarded after aggregation.

## How to interpret results

1. A flat plateau around 27–39k RPS shared by FastFrame and Micronaut may reflect the **load generator or host CPU** rather than framework capacity. Monitor load-generator utilization, run from a second machine, and test without CSV output as a cross-check.
2. Look at **p99** with RPS. A rising p99 and falling RPS around 256–1024 clients indicate saturation or overload; inspect server logs and HTTP errors.
3. A single repeat cannot prove a small percentage performance advantage. Prefer **3–5 repeats**, alternating framework order between repeats; use medians and ranges.
4. Laravel Octane launches worker child processes. Its CPU/RSS values are intentionally blank (`n/a`) instead of misleadingly counting only the parent process. Report PHP version, FrankenPHP/Octane and configured worker count for reproductions.
5. Relative throughput is valid only when all frameworks serve the exact same endpoint semantics, responses and success status. Preflight verifies all four cases before measurement.
6. The benchmark uses `hey` (closed-loop fixed concurrency), **not** a fixed arrival rate. To assess coordinated omission and SLO limits, use a separate open-loop test (e.g., k6 constant-arrival-rate) with load generator resource monitoring.

## Tests

```bash
python3 -m unittest discover -s benchmarks/tests -v
python3 -m py_compile benchmarks/concurrency.py benchmarks/concurrency_report.py
```

No production code or HTTP endpoint semantics are changed by this benchmark addition. The runner requires compiled benchmark JARs / installed PHP packages to perform real HTTP tests. No simulated benchmark results are included as actual results.

## Windows listener detection and shared benchmark ports

A benchmark must not mistake a previous server for its own. On Windows,
`connect_ex()` alone is not a reliable indication of an active listener when
the server's accept backlog is full. The runner reads `netstat -ano` TCP
LISTENING entries before deciding whether a requested port is safe to use.
It considers IPv4 and IPv6 listener entries; the check does not open TCP
connections. If binding fails and the Windows socket table cannot be
reliably inspected, the runner fails with a diagnostic error instead of
silently changing ports. A genuinely stale TIME_WAIT socket may still
trigger selection of a free port when netstat confirms no listener.

When troubleshooting on Windows, inspect the listener and PID:

```powershell
netstat -ano | findstr :18080
```

The runner does **not** automatically kill another process using the port.
