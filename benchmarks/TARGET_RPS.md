# FastFrame v0.22 — Target RPS / p99 (fixed arrival rate)

This is the **open-loop** counterpart to [`CONCURRENCY.md`](CONCURRENCY.md).
Both suites run **FastFrame, Micronaut and Laravel Octane**, sequentially, with
identical `plain`, `json`, `route`, `echo` HTTP endpoints and preflight assertions.
This addition changes **benchmark infrastructure only** (no Java/PHP production
routing code).

## Design

- Load driver: **Grafana k6 `constant-arrival-rate`**, `timeUnit: 1s`.
- Default target rates: **1,000, 2,000, 4,000, 8,000, 12,000, 16,000,
  20,000, 24,000, 30,000 HTTP requests per second**.
- **60 seconds per measured target level**, with a 5-second warmup per endpoint
  and framework (at the lowest target rate), 5-second k6 graceful stop.
- Four independently measured workloads per framework: plain, JSON,
  dynamic route and POST echo.
- Virtual users: pre-allocated `128`, max `512` (configure `--vus` and
  `--max-vus` based on your hardware; insufficient VUs can cause drops).
- Servers are **never benchmarked concurrently**. Preferred port 18080;
  fallback for stale TCP states; an active listener causes a safe refusal.
- Per-measurement JSON metrics from k6 are transformed into a stable schema
  by `target_rps.js`, rather than scraping console output. All CSV/JSON files
  are checkpointed after each measurement.
- Alternating framework order across repeats. The result report is independent
  of `hey` and the existing concurrency suite.

### Install on macOS

```bash
brew install k6 hey php composer
k6 version
cd ~/Downloads/netty-laravel-starter

# Build benchmark JARs and install Laravel Octane/FrankenPHP:
bash benchmarks/build.sh --with-laravel
python3 benchmarks/preflight.py --frameworks fastframe micronaut laravel
```

### Quick sanity check (short measured run)

```bash
python3 benchmarks/target_rps.py \
  --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --cases plain --rates 100 500 --duration 3s --warmup 1s \
  --vus 32 --max-vus 64 --repeats 1
```

### Full test (60 seconds per level)

```bash
python3 benchmarks/target_rps.py \
  --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --rates 1000 2000 4000 8000 12000 16000 20000 24000 30000 \
  --cases plain json route echo \
  --duration 60s --warmup 5s --vus 128 --max-vus 512 --repeats 1
```

With these settings the measured portion is **3 × 4 × 9 × 60s = 108 minutes**,
plus warmups, server lifecycle, k6 startup and cool-down. Three repeats take
at least **324 measured minutes**. If testing first on a laptop, use one case
and 3–4 rates, then widen the matrix after checking the generator CPU.

**Windows:**

```powershell
.\benchmarks\target-rps.ps1 -Frameworks fastframe,micronaut,laravel `
  -LaravelServer octane -LaravelWorkers 4 `
  -Rates 1000,2000,4000,8000,12000,16000,20000,24000,30000 `
  -Duration 60s -Warmup 5s -Repeats 1 -SkipBuild -SkipPreflight
```

**Linux/macOS wrapper:** `bash benchmarks/target-rps.sh --cases plain --rates 1000 2000 --duration 60s`.

## Reports

Under `benchmarks/results/target-rps-<timestamp>/`:

- `environment.json` — exact rates, framework order, k6 version, PHP/Java versions,
  available CPU cores, VU budget, Laravel worker count and flags.
- `target-rps-results.csv` / `.json` — per-run completed requests, actual RPS,
  k6-reported wall-time RPS, dropped iterations, p50/p95/p99, failed responses,
  target delivered % and success flag.
- `target-rps-summary.csv` / `.json` — per-framework/endpoint/rate medians,
  completeness flag and target success across repeats.
- `report.md` and **offline `report.html`** — tables and line plots for
  achieved RPS and p99 versus target RPS. A dashed line denotes ideal achieved
  throughput. Hollow points indicate missed targets, rather than disappearing
  from the graph.
- Optional `--keep-k6` retains raw k6 summaries and logs per level.

Regenerate reports without rerunning HTTP:

```bash
python3 benchmarks/target_rps_report.py benchmarks/results/target-rps-<timestamp>
open benchmarks/results/target-rps-<timestamp>/report.html
```

## How to interpret results

- **Achieved RPS** is counted completed HTTP requests divided by the requested
  measured duration (60s by default), not a guessed throughput capacity. The
  k6 summary's separately measured wall-time request rate is also retained.
- **Target met** means 95–105% of scheduled requests were completed, no dropped
  iterations and no failed HTTP requests, across **all repeats**. Overload
  measurements are shown as *missed*, not silently discarded.
- **p99 covers only requests that were actually sent and completed.** When
  k6 drops planned iterations, these are not reflected in p99. Always inspect
  both p99 and `dropped_iterations`.
- k6's 5s graceful-stop grace allows in-flight requests to finish after the
  target window; at severe overload prefer inspecting detailed request timelines
  rather than interpreting `count/60` as perfectly bounded-time throughput.
- High dropped_iterations can come from the **generator's VU allocation, CPU
  saturation, or server latency**. Increasing `--vus` or running k6 on another
  computer helps distinguish sources.
- A missed high target is *not automatically evidence of server failure*.
  Re-test with a dedicated load generator, compare CPU and system metrics.
- k6's `http_req_duration` measures HTTP request time, while overall request
  schedules also include JS iteration overhead. Do not infer DB performance
  from these minimal endpoints.
- For latency SLOs, identify the highest target that simultaneously meets
  desired p99, zero dropped iterations and an acceptable error budget.
- This suite does **not** claim comparable process-tree memory or CPU numbers:
  Laravel workers make parent-only observations misleading, and we do not
  report cross-framework per-process figures in the k6 mode.

## Tests (no k6 installation required)

```bash
python3 -m unittest discover -s benchmarks/tests -v
python3 -m py_compile benchmarks/target_rps.py benchmarks/target_rps_report.py
node --check --input-type=module < benchmarks/target_rps.js
```

For actual network measurements, install all dependencies and launch the
benchmarks on your machine. **No synthetic/mock test data should be reported
as measured RPS.**

References:
- https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/
- https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/dropped-iterations/
