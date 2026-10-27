# FastFrame v0.22 — Async I/O & JWT benchmark

The fifth benchmark compares **FastFrame**, **Micronaut** and **Laravel Octane**.
The existing concurrency, open-loop target RPS/p99, JSON-size, and real PostgreSQL/Redis tests remain available and unchanged.

## Workloads and HTTP contract

| Case | Request | Response | Backend action |
|---|---|---|---|
| `async_10` | GET `/bench/async/10` | `{"value":"ok","delay_ms":10}` | HTTP GET `/wait/10`; upstream delays 10 ms |
| `async_50` | GET `/bench/async/50` | `{"value":"ok","delay_ms":50}` | HTTP GET `/wait/50`; upstream delays 50 ms |
| `async_200` | GET `/bench/async/200` | `{"value":"ok","delay_ms":200}` | HTTP GET `/wait/200`; upstream delays 200 ms |
| `jwt` | GET `/bench/jwt` with `Authorization: Bearer ...` | `{"subject":"bench-user","authorized":true}` | Recompute HS256 HMAC + validate canonical fixture claims and expiry |

The upstream is a **real, separate asynchronous HTTP/1.1 server** (`upstream.py`) on `127.0.0.1:19090` by default. It uses `asyncio.sleep()` **inside the upstream**, not `Thread.sleep()` in the framework endpoint. FastFrame and Micronaut use `java.net.http.HttpClient.sendAsync()` and return `CompletionStage`; Laravel uses its HTTP client (`Http::timeout(5)->get()`) on an Octane worker. Every endpoint validates the upstream status and JSON body before responding. The runner resets the upstream counters between samples and asserts that the counted upstream completions cover successful client requests.

The JWT token is freshly issued by the runner using a random per-run HMAC secret. It is supplied to `hey` using the `Authorization` header; each server **verifies the HMAC for every incoming request**, checks strict claims, `iat`, `nbf`, `exp` and returns HTTP 401 for missing/altered/expired tokens. Preflight performs both positive and negative tests. **This is a constrained common HS256 benchmark, not a complete end-to-end security middleware benchmark or a production JWT library.** Do not reuse the fixture verifier for real application authentication.

This benchmark measures external-wait concurrency and JWT verification separately. **JWT is not required for the async endpoints**; combining those workloads would confound the comparison.

## Requirements

- Java 21 and Maven for the two Java benchmarks; PHP 8.3+/Composer/FrankenPHP for Laravel Octane.
- Python 3.10+, `hey`, available loopback ports 18080 and 19090.
- No Docker, PostgreSQL, Redis or k6 required **for this fifth suite**.
- Install/build all targets with `bash benchmarks/build.sh --with-laravel`, or PowerShell `./benchmarks/build.ps1 -IncludeLaravel -LaravelServer octane`.

## Quick smoke on macOS/Linux

```bash
bash benchmarks/build.sh --with-laravel
python3 benchmarks/async_jwt.py --frameworks fastframe micronaut laravel \
  --laravel-server octane --cases async_10 jwt \
  --duration 2s --warmup 0s --concurrency 8 --repeats 1
```

## Full benchmark on macOS/Linux

```bash
python3 benchmarks/async_jwt.py \
  --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --cases async_10 async_50 async_200 jwt \
  --duration 30s --warmup 5s --concurrency 64 --repeats 3 \
  --heap 512m
```

That is **18 minutes of measurement** (`3 frameworks × 4 cases × 3 repeats × 30 s`) plus startup and warmup.

Windows PowerShell:

```powershell
.\benchmarks\async-jwt.ps1 -Frameworks fastframe,micronaut,laravel `
  -Cases async_10,async_50,async_200,jwt -Duration 30s `
  -Warmup 5s -Concurrency 64 -Repeats 3 -LaravelServer octane
```

For a short Laravel development-server smoke test (not a production comparison), select `--frameworks laravel --laravel-server artisan`.

## Results, statistical validity, and limitations

Results land in `benchmarks/results/async-jwt-<timestamp>/`:

- `async-jwt-results.csv` and `.json`: complete individual measurements (RPS, p50/p95/p99, errors, sampled Java RSS/CPU, observed upstream completions).
- `async-jwt-summary.csv` and `.json`: complete-group medians; invalid or incomplete groups flagged, never used in ratio calculations.
- `report.html`: standalone SVG charts for median RPS and p99, three frameworks per workload.
- `report.md`: comparison table, ratio summary, and methodology.
- `environment.json`: versions, runtime options, upstream port and **hash of the JWT secret only**. Neither the secret nor the signed token is written to the reports.

**Important caveats:** Java async completion and PHP Octane synchronous worker waits are different concurrency models. Use equivalent outbound semantics but expect different scaling under long external waits. The upstream and load generator share the same machine by default; their load may limit high-concurrency results. Java uses a single process for sampled RSS/CPU; Octane has child workers and resource metrics for Laravel are null (unknown, not zero). With `hey -o csv`, client-side logging can limit high RPS. HTTP/1.1 keep-alive is available but connection pooling varies across runtimes. Use at least three independent repeats for publication-grade claims.

The test has **no measured benchmark results included**; running it on your Mac produces the genuine values.

## Windows cross-runtime JWT unit test (`javac --release 21`)

The regression test compiles two standalone `BenchJwt.java` classes with a
JDK 21+ `javac`, then executes a Java probe. On Windows, `java` and `javac`
may resolve to different installations through `PATH`. The test now looks in
`JAVA_HOME/bin` first and uses the matching runtime from the same directory.
If JDK 21+ is absent, that individual test is skipped with the detected
compiler versions. If a compatible compiler fails, the test prints its full
`stdout`, `stderr`, and invocation instead of an opaque `CalledProcessError`.

PowerShell diagnostic commands:

```powershell
where.exe java
where.exe javac
java -version
javac -version
$env:JAVA_HOME
python -m unittest discover -s benchmarks/tests -p test_async_jwt.py -v
```

Make `JAVA_HOME` point to a **JDK** (not just a JRE), preferably JDK 21.
CI should install JDK 21 so the cross-runtime test actually executes.
