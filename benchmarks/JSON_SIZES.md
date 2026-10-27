# FastFrame v0.22 — JSON Sizes: 1 KiB / 10 KiB / 100 KiB / 1 MiB

Third benchmark category, alongside [Concurrency Scaling](CONCURRENCY.md) and [Target RPS / p99](TARGET_RPS.md). Compares **FastFrame / Netty**, **Micronaut / Netty**, **Laravel Octane / FrankenPHP** with consistent HTTP response bodies.

## Test definition

| Label | Exact UTF-8 JSON size | GET path |
|---|---:|---|
| `1kb` | 1,024 bytes | `/bench/json-size/1kb` |
| `10kb` | 10,240 bytes | `/bench/json-size/10kb` |
| `100kb` | 102,400 bytes | `/bench/json-size/100kb` |
| `1mb` | 1,048,576 bytes | `/bench/json-size/1mb` |

Every endpoint serializes a map/object of the form `{"data":"xxxxx..."}`. The map is populated with a **precomputed, immutable ASCII string** so all three implementations perform comparable JSON encoding per HTTP request without also allocating or computing a new large source string. Neither FastFrame nor Micronaut nor Laravel sends a cached serialized JSON response. All four sizes are exact UTF-8 lengths of the *whole JSON document*, not approximate lengths of the data property.

The **1 MiB JSON case exactly reaches FastFrame's current `Json.MAX_CHARS` limit**; larger documents would require a change to its configured serialization limit. Do not silently raise that limit just for benchmarks.

## macOS / Linux

Install: Java 21, Maven, Python 3.10+, PHP 8.3+, Composer, `hey` (`brew install hey` or `go install github.com/rakyll/hey@latest`). To include Laravel Octane with FrankenPHP, setup needs Packagist/Composer access.

```bash
cd ~/Downloads/netty-laravel-starter
bash benchmarks/build.sh --with-laravel
python3 benchmarks/preflight.py --frameworks fastframe micronaut laravel
```

Fast installation check (~a few seconds plus startups):

```bash
python3 benchmarks/json_sizes.py \
  --frameworks fastframe micronaut laravel \
  --sizes 1kb 10kb \
  --duration 2s --warmup 1s --concurrency 8 --repeats 1
```

Full benchmark, default 30 s per size, 64 concurrent clients, 3 repetitions:

```bash
python3 benchmarks/json_sizes.py \
  --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --sizes 1kb 10kb 100kb 1mb \
  --duration 30s --warmup 5s \
  --concurrency 64 --repeats 3 --heap 512m
```

The measured portion is **18 minutes** (3 frameworks × 4 sizes × 3 runs × 30 s), plus warmups and process startup. Use `--sizes 1mb` to isolate large-payload behavior; at high throughput this can saturate memory bandwidth, TCP and the load generator.

To run Laravel development server for diagnostics (not a production comparison), use `--laravel-server artisan`.

## Windows

```powershell
.\benchmarks\json-sizes.ps1 -Frameworks fastframe,micronaut,laravel -LaravelServer octane `
  -Sizes '1kb','10kb','100kb','1mb' -Duration 30s -Warmup 5s `
  -Concurrency 64 -Repeats 3
```

**PowerShell note:** Quote size labels such as `'1kb'` and `'1mb'`. Without quotes, PowerShell parses `1kb` as the integer `1024` before passing it to the script. The wrapper accepts and normalizes the four exact numeric equivalents (`1024`, `10240`, `102400`, `1048576`) for compatibility, but quoting is recommended. Invalid sizes are rejected.

`-SkipBuild` and `-SkipPreflight` skip optional steps. PowerShell uses the project's Java 21 selector and does not change the system default JDK.

## Outputs and validation

`benchmarks/results/json-sizes-<UTC-timestamp>/` contains:

- `json-sizes-results.csv` / `.json` — all independent runs, checkpointed after every size.
- `json-sizes-summary.csv` / `.json` — medians of completed repetitions, including error/incomplete status.
- `report.html` — fully offline SVG charts: RPS vs size, payload MiB/s vs size, p99 vs size.
- `report.md` — tables, valid-only RPS ratios, caveats.
- `environment.json` — run parameters and expected SHA-256 hashes of each canonical fixture.
- `<framework>-run-N.log` — each server's stderr/stdout.
- Raw per-request hey CSV is removed by default, retained with `--keep-raw`.

**Before each timed test** the runner validates HTTP 200, `application/json`, the exact bytes/length, JSON structure, SHA-256 (recorded) and Content-Length when provided. The timed load uses `hey -o csv`, tracking 2xx/non-2xx, latency and RPS. The load phase **does not inspect every response body**; the preflight is a representative check.

`MiB/s = (RPS × JSON payload bytes) / 1,048,576`. It measures *delivered application payload*, not actual TCP traffic or upload bandwidth. Ignore incomplete or error-bearing groups for performance comparisons. Laravel RSS/CPU is null because process-tree aggregation is not implemented. Test machine and the generator share CPU resources by default. Run ≥3 repetitions (even/odd runs alternate server order) to limit ordering bias. Consider separate load-generator host for publishable measurements.

## Unit/regression checks

```bash
python3 -m unittest discover -s benchmarks/tests
php -l benchmarks/laravel-app/routes/bench.php
```

Full Maven builds and actual HTTP throughput need dependencies and servers on the benchmark host.
