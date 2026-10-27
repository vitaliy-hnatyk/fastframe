# Database & Redis — FastFrame v0.22

**Real PostgreSQL 16.6 + Redis 7.4.2** workloads for FastFrame 0.22, Micronaut and Laravel Octane. There are no H2, embedded Redis, array-store or mocked data-path fallbacks.

## Prerequisites

macOS: Java 21, Maven, PHP 8.3+, Composer, Python 3.10+, `hey`, Docker Desktop (running, Compose v2). Install `hey` via `brew install hey`. Laravel requires the `pdo_pgsql` PHP extension (`php -m | grep pdo_pgsql`). Laravel Octane uses FrankenPHP; `benchmarks/build.sh --with-laravel` installs it via Composer (internet needed). Docker Desktop on macOS incurs virtualization overhead; run competitors sequentially and compare within the same environment.

Windows: Docker Desktop (Linux containers), JDK 21, PowerShell, Maven, PHP, Composer, Python, `hey.exe`. See `benchmarks/db-redis.ps1`.

The Compose services expose **only loopback** ports: PostgreSQL `127.0.0.1:55432` and Redis `127.0.0.1:56379`. Credentials are **benchmark-only `ffbench/ffbench`**, never deploy these services publicly. Docker Compose images are fixed; do not change between comparator runs.

```bash
cd ~/Downloads/netty-laravel-starter
bash benchmarks/build.sh --with-laravel
# quick smoke, real PostgreSQL and Redis; generates reports
python3 benchmarks/db_redis.py \
  --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --cases select cache_get --duration 2s --warmup 0s \
  --concurrency 8 --repeats 1

# full run; six cases * three servers * three repeats * 30s = 27 minutes of measured load
python3 benchmarks/db_redis.py \
  --frameworks fastframe micronaut laravel \
  --laravel-server octane --laravel-workers 4 \
  --cases select insert update transaction cache_get cache_set \
  --duration 30s --warmup 5s --concurrency 64 --repeats 3 \
  --db-pool 16 --io-workers 32 --heap 512m
```

`docker compose -f benchmarks/services/compose.yaml down` stops services. `--stop-services` stops them on exit. The services are independent of the normal HTTP benchmarks.

## Precisely defined work

| Case | HTTP | Operation / resulting JSON |
|---|---|---|
| `select` | GET `/bench/db/select` | PostgreSQL `SELECT payload FROM bench_lookup WHERE id=?` with id 1 → `{"value":"ok"}` |
| `insert` | POST `/bench/db/insert` | `INSERT INTO bench_events (payload) VALUES (?)`, payload `ok` → `{"ok":true}` |
| `update` | POST `/bench/db/update` | `UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?`, id 1 → `{"ok":true}` |
| `transaction` | POST `/bench/db/transaction` | Two updates for id 1 and id 2 on one connection, COMMIT or ROLLBACK → `{"ok":true}` |
| `cache_get` | GET `/bench/cache/get` | Redis `GET ffbench:hot` → `{"value":"ok"}` |
| `cache_set` | POST `/bench/cache/set` | Redis `SET ffbench:set ok` → `{"ok":true}` |

All SQL is parameterized; an isolated test database is initialized with `schema.sql`. UPDATE and TRANSACTION intentionally contend on 1–2 hot rows, which can make lock waiting, rather than HTTP framework speed, the main bottleneck. Redis operations are real TCP requests. **Laravel uses Predis 2.x** because it works without a PHP extension; Java uses Lettuce. The different Redis client libraries are part of the ecosystem comparison, not identical client implementations.

FastFrame runs blocking JDBC/Redis operations on a dedicated bounded `ThreadPoolExecutor` (never in Netty event loops), and Micronaut uses `@ExecuteOn(BLOCKING)`. Both use HikariCP, PostgreSQL JDBC, and `BENCH_DB_POOL=16`; Laravel uses the PDO PostgreSQL connector with per-worker connection reuse. Laravel `laravel-workers=4` is separate from the Java worker count. Changing the worker count or pool size should be documented before comparing scores.

### Reset and validity

Before **every case and repeat**, the runner truncates `bench_events`, zeros both counters and seeds Redis `ffbench:hot=ok`. It executes a response preflight and a warmup, **resets again**, then measures. It checks exact persisted write counts against completed successful HTTP requests and validates Redis values after each measurement. Incomplete repeats, any non-2xx, or invalid side effects remain visible but are excluded from valid speed ratios. This is not isolated per HTTP client (the deliberately shared hot rows are part of the test).

`hey -o csv` records requests and consumes client CPU/disk. For scientific benchmark results, run the load generator on a separate physical system; PostgreSQL and Redis should have dedicated resources. Keep versions, JVM heap, worker count, connection pool, OS state and server order recorded in `environment.json`.

### Output

`benchmarks/results/db-redis-<timestamp>/` includes:

- `db-redis-results.csv`, `db-redis-results.json`: individual raw measurement summaries and state assertions.
- `db-redis-summary.csv`, `db-redis-summary.json`: complete-only median RPS, p95/p99, errors, optional RSS/CPU.
- `report.html`: offline RPS and p99 SVG charts per operation.
- `report.md`: tables, validity, ratios and methodology.
- `environment.json`, `*.log`: reproducibility and diagnostics.

**No RPS claims are made until a real full HTTP run has completed.** This suite coexists with existing `concurrency.py`, `target_rps.py` and `json_sizes.py`.

CI includes a **real PostgreSQL/Redis integration smoke** for all three application route implementations. Laravel is started using `artisan serve` in CI; **production Octane/FrankenPHP is the default local performance target** and still requires an external Octane-enabled host to validate throughput.
