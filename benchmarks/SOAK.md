# FastFrame v0.22 — 60-minute Soak Benchmark

Sixth benchmark in the same suite as Concurrency Scaling, Target RPS/p99, JSON Sizes, Database & Redis, and Async I/O & JWT. Works with **FastFrame, Micronaut and Laravel Octane** and reuses the **identical** `plain`, `json`, `route`, and `echo` HTTP handlers of the previous benchmark builds. The previous five benchmark runners are unchanged.

## Purpose

Measure **stability during constant offered load**, not maximum throughput. Each framework runs individually at the *same fixed arrival rate* for 60 minutes (3 hours total measured load for the default three frameworks, **plus warmup and startup**). The metrics are:

| Metric | Source | Important interpretation |
|---|---|---|
| RPS, p50/p95/p99, HTTP errors, dropped iterations | k6 `constant-arrival-rate` final summary | `dropped_iterations` may be a k6 VU shortage or server slowdown |
| RSS (MiB), start/peak/end, slope MiB/hour | `psutil` process tree, ~10s | Sum of resident pages across parent and workers; shared pages may be counted multiple times |
| CPU usage (CPU cores) | `psutil` sum of CPU times across live processes | `1.0` ≈ one saturated logical core; newly launched/terminated workers can slightly bias sampling |
| JVM heap used/capacity (MiB) | JDK `jstat -gc` | Java only; excludes metaspace, direct Netty buffers and other native allocations |
| JVM cumulative GC collections/time | `jstat -gc` | Java only; cumulative GC time is not maximum GC pause latency |
| Probe failures over time | Independent `/bench/plain` probe (default 15s) | These errors are **separate from** HTTP errors reported by k6 |

**PHP** (Laravel Octane): JVM heap and GC are **N/A**, not zero. Workers are included in sampled RSS/CPU as descendants of the launcher. This is aggregate RSS and can double-count pages shared by workers. A worker that daemonizes outside the launcher process tree may not be captured; check process tree on your host. For per-process PHP memory, separately use PHP-FPM/FrankenPHP-specific instrumentation. 

## Requirements

- Java 21 and Maven; `jstat` available from the full JDK for Java heap/GC.
- `k6` (macOS `brew install k6`; Windows `winget`/`choco` if supported by your environment).
- Python **3.10+**, Python package **psutil** (`python3 -m pip install psutil`, or install it inside a venv).
- PHP + Composer + FrankenPHP/Octane when testing Laravel.
- Built benchmark JARs and Laravel installation, as in `benchmarks/README.md`.
- Prefer a dedicated host for load generation. When running k6 and the server on the **same Mac**, the k6 process competes for CPU and the measurements are host-limited.

### macOS / Linux

```bash
cd ~/Downloads/netty-laravel-starter
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install psutil
brew install k6 hey                # macOS (Linux: install via distro-specific instructions)
bash benchmarks/build.sh --with-laravel
python3 benchmarks/preflight.py --frameworks fastframe micronaut laravel
```

Smoke run (short duration validates the harness, **not** a memory-leak test):

```bash
python3 benchmarks/soak.py \
  --frameworks fastframe micronaut laravel \
  --case json --rate 500 --duration 5s --warmup 1s \
  --sample-interval 1 --gc-interval 1 --probe-interval 1
```

**Real 60-minute soak per framework**:

```bash
python3 benchmarks/soak.py \
  --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --case json --rate 1000 --duration 3600s --warmup 15s \
  --vus 128 --max-vus 512 \
  --sample-interval 10 --gc-interval 20 --probe-interval 15 \
  --heap 512m --repeats 1
```

`--rate 1000` is a **starting offered rate**, not an observed performance claim. Choose a rate that all three frameworks can sustain (from `target_rps.py`) before running a fair soak. For overload testing, run a separate series above saturation; do not confuse it with a stable-load comparison.

To test different endpoints, use `--case plain`, `--case route`, or `--case echo`; **each case requires a separate soak run**. To reduce thermal and order bias use `--repeats 2` or more, which reverses framework order on even repetitions.

### Windows PowerShell

```powershell
.\benchmarks\soak.ps1 -Frameworks fastframe,micronaut,laravel -Case json -Rate 1000 -Duration 3600s
```

To skip compiling: `-SkipBuild`. Ensure `python`/`py` used by `Invoke-FastFramePython` can import `psutil`; install it in **that same Python environment**.

## Outputs

Results are written to `benchmarks/results/soak-YYYYMMDDTHHMMSSZ/`:

- `soak-timeseries.csv` and `.json`: one sampled row per interval with RSS, heap, CPU, JVM GC and probe failures.
- `soak-results.csv` and `.json`: final k6 outcomes plus memory summary; **partial/incomplete** rows are preserved on interruptions.
- `soak-summary.csv` and `.json`: report statuses and key comparisons.
- `report.html`: self-contained offline SVG graphs (RSS, JVM heap, CPU, JVM GC collections/time, probe errors), plus summary table.
- `report.md`: compact comparison table and limitations.
- `environment.json`: OS, selected frameworks, target rate, duration, process sampling, JVM and launcher metadata.
- Per-framework server/k6 log files. Optional `--keep-k6` preserves raw k6 summary JSON.

Open the last report on macOS:

```bash
REPORT=$(find benchmarks/results -path '*/soak-*/report.html' | sort | tail -1)
open "$REPORT"
```

## Judging a run

`COMPLETE` = all load requests were accounted for, **no** HTTP errors, **no** dropped k6 iterations, delivered RPS within the 95–105% acceptance window, and resource samples were captured. `TARGET_MISSED`, `ERRORS_OR_DROPS`, `PROBE_FAILURES`, `UNMONITORED`, and `INCOMPLETE` must never be claimed as successful benchmarks.

A positive RSS trend is **not** automatically a leak: warm caches, JIT code, class loading, changing request concurrency, OS page accounting, or PHP worker recycling can explain it. Look for repeated steady-state growth (after warmup), corroborate with a heap dump/JFR for Java, and test after idle recovery. `jstat` failures are represented by blank heap/GC values, never invented zeros.

The default path tests a single process of each framework and a single instance of the same endpoint. It does not represent production DB/Redis traffic or prove correctness of an entire application. Use `DATABASE_REDIS.md`, `ASYNC_JWT.md`, and `TARGET_RPS.md` for complementary load patterns.
