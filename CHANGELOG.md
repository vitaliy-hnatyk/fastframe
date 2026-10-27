## v0.22.0 — PostgreSQL benchmark initialization CI hotfix (2026-10-27)

- Fix `benchmarks/services/schema.sql`: seed `bench_counter.counter_value`, not the nonexistent `value` column; the previous script made `postgres:16.6-alpine` exit during first initialization.
- Make PostgreSQL Docker health checks wait for the final TCP listener **and** the two seeded counter rows (not just a temporary init socket).
- Recreate the dedicated `fastframe-bench` Compose containers with fresh anonymous volumes before benchmark runs to avoid carrying half-initialized databases into retries.
- Print `docker compose ps -a` and the last 100 lines of PostgreSQL/Redis logs when startup fails; with `--stop-services`, clean up even after startup failure (`down --volumes --remove-orphans`).
- Add five benchmark integration-contract regression tests: seed SQL, schema health, fresh startup, actionable logs and cleanup. PostgreSQL/Docker integration still needs CI or a host with Docker.
- No runtime framework/HTTP endpoint changes, and no benchmark RPS claims.

## v0.22.0 — Benchmark source smoke CI hotfix (2026-10-27)

- Fix `scripts/benchmark-smoke.sh` on GitHub Actions: its standalone `javac` compilation now targets explicitly listed core HTTP/JSON/async/JWT benchmark sources rather than compiling DB/Redis integration classes without their Maven dependencies.
- Keep `DbRedisWorkload` / `DbRedisController` in the **full** `benchmark-fastframe` Maven package build; no production endpoint or Maven dependency removed.
- Add `test_benchmark_smoke.py` guarding the zero-external-dependency javac scope and the separate full Maven CI step.
- Label the CI smoke step to reflect its scope; retain Python benchmark suite execution in the smoke script.
- The standalone smoke and all 99 Python tests pass on Linux/Java 21 (two platform-specific tests skipped). Full Maven build is not available in the local environment and must be verified by GitHub Actions.

## v0.22.0 — Windows CI benchmark compatibility hotfix (2026-10-27)

- Use explicit UTF-8 decoding for benchmark report fixture reads on Windows (including the `×` ratio sign), matching existing UTF-8 report writers.
- Treat WinSock `10013`/EACCES as an unavailable/restricted TCP port, not harmless TIME_WAIT; avoid `SO_REUSEADDR` on Windows to prevent an accidental shared port.
- Run POSIX `ps` integration tests only on Unix; preserve native WinAPI tests for Windows RSS and CPU measurements.
- Add regression coverage for Windows port access-denied responses, native metrics selection and report UTF-8 parity.
- No changes to Netty, Java benchmarks, HTTP routes, or benchmark metric calculations.

## v0.22 — k6 fixed-arrival warmup hotfix

- Fix `ReferenceError: maxVUs is not defined` in the shared `benchmarks/target_rps.js` workload by mapping the k6 `maxVUs` option to the defined `maxVus` value.
- This fixes startup for both `benchmarks/target_rps.py` and `benchmarks/soak.py`, including warmup and measured runs.
- Add Node.js mock-runtime regression testing of scenario initialization, one request, and `handleSummary` output to catch runtime identifier errors (which `node --check` misses).
- Preserve the Micronaut Maven dependency fix and all six benchmark suites.

## v0.22 — Async I/O & JWT benchmark addition (fifth suite)

- Implemented asynchronous external HTTP waits (10, 50 and 200 ms) in FastFrame/Micronaut;
  equivalent real outbound HTTP calls in Laravel Octane.
- Added benchmark-only HS256 JWT verification with claim and expiry validation in all three targets;
  positive, missing/forged/expired token preflights (HTTP 401).
- Added a loopback asyncio upstream process with resettable per-delay completion counters
  to verify that outbound network I/O was actually performed.
- Added `benchmarks/async_jwt.py`, `async_jwt_report.py`, `upstream.py`,
  shell/PowerShell wrappers, unit and HTTP integration tests, CI smoke tests,
  HTML/Markdown/CSV/JSON reports and methodology documentation.
- Preserved the earlier v0.22 concurrency, target RPS, JSON-size, PostgreSQL/Redis suites.
- No performance claims without a real multi-framework benchmark run.

## v0.22 — Database + Redis Benchmark Extension

- Added real PostgreSQL 16.6 + Redis 7.4.2 Docker Compose benchmark services and deterministic fixtures.
- Added SELECT, INSERT, UPDATE, transactional double UPDATE and Redis GET/SET HTTP routes to FastFrame, Micronaut and Laravel 13 Octane benchmarks.
- FastFrame JDBC/Redis calls offloaded to a bounded worker pool; Micronaut offloaded to its blocking executor; both use HikariCP with matching pool sizes. Laravel uses parameterized PDO and Redis Predis.
- Added `benchmarks/db_redis.py` runner (same hey load generator), persisted-state validation, atomic checkpoints and `db_redis_report.py` CSV/JSON/Markdown/offline HTML.
- Added cross-platform scripts, documentation, Python regression tests and opt-in Docker Compose CI smoke.
- Preserved prior Concurrency Scaling, Target RPS/p99, and JSON Sizes modes without changes.

## v0.22.0-SNAPSHOT — JSON Sizes Benchmark

- Add GET `/bench/json-size/{size}` to FastFrame, Micronaut and Laravel benchmark applications.
- Produce 1,024, 10,240, 102,400, and 1,048,576 exact bytes of identical UTF-8 JSON; precompute immutable source strings, serialize JSON per request.
- Add `json_sizes.py`: SHA/body/length validation, hey HTTP timing, RPS, payload MiB/s, p50/p95/p99, error count, OS resource metrics (excluding misleading Laravel parent-only memory), sequential server cleanup and recoverable results.
- Add `json_sizes_report.py`: validity-aware median summaries; offline HTML SVG curves for RPS, MiB/s and p99; CSV, JSON and Markdown.
- Add macOS/Linux/Windows launchers, tests, CI checks and `benchmarks/JSON_SIZES.md`. Preserve Concurrency Scaling and Target RPS / p99 unchanged.
- Full three-server HTTP RPS results still require the user's local build and load-generator environment.

## v0.22.0-SNAPSHOT — Target RPS / p99 (Open-Loop) + Concurrency Scaling

- Keep existing concurrency scaling suite (1–1,024 clients, 30 seconds per level), three servers, four endpoints and existing reports.
- Add `benchmarks/target_rps.py` with Grafana k6 `constant-arrival-rate` for controlled 60-second target RPS/p99 sweeps across FastFrame, Micronaut and Laravel Octane.
- Add standalone k6 workload and versioned summary, dropped-iteration/error tracking, measured-vs-target delivery, safe server teardown, and recoverable CSV/JSON checkpoints.
- Add `target_rps_report.py`: complete/incomplete repetition accounting, median summaries, offline HTML with achieved RPS and p99 curves, plus Markdown/CSV/JSON.
- Add macOS/Linux and PowerShell launchers, tests for k6 parsing/under-delivery/HTTP failures/process cleanup and documentation in `benchmarks/TARGET_RPS.md`.
- No Java/PHP runtime code changed; real three-server k6 performance figures must be measured on a machine with k6/Composer/Maven installed.

## v0.22.0-SNAPSHOT — Concurrency Scaling Benchmark Suite

- Add `benchmarks/concurrency.py`: 9 configurable concurrency levels (1–1,024), 30 seconds measured per level, for FastFrame/Micronaut/Laravel Octane on the same four endpoints.
- Sequential server lifecycle with automatic port reuse fallback, endpoint preflight, warmup at each level, and guaranteed subprocess cleanup.
- Export checkpointed CSV/JSON results after every completed level; default to deleting large raw `hey` sample files unless `--keep-raw` is selected.
- Add `concurrency_report.py` for median summaries, validity checks and standalone offline HTML RPS/p99 graphs per scenario; report interruption as incomplete, never as zero RPS.
- Add macOS/Linux shell and Windows PowerShell launchers, GitHub CI smoke, regression coverage and `benchmarks/CONCURRENCY.md`.
- No runtime framework/HTTP endpoint logic changed; no cross-framework benchmark results are claimed until measured on the target machine.

## Unreleased — benchmark TCP port reuse fix (v0.21)

- Fix macOS `Errno 48` during sequential preflight after FastFrame shuts down.
- Probe ports with `SO_REUSEADDR` and `listen()` to avoid false positives from stale TCP state.
- Refuse active listeners; when no listener exists but the preferred port is still
  temporarily unbindable, select a free ephemeral loopback port.
- Propagate the selected port to Java and Laravel servers and record it in results CSV.
- Add regression tests for occupied ports and lingering TCP connections.

## Unreleased — Laravel benchmark integration (v0.21)

- Added a reproducible Laravel 13 (PHP) benchmark fixture with matching plain, JSON,
  route and echo endpoints, including JSON request parsing.
- Added Composer setup and optional Laravel Octane/FrankenPHP mode, with explicit
  development-server mode for smoke testing.
- `benchmarks/run.py`, `preflight.py`, Windows PowerShell scripts and the summarizer
  now support selecting Laravel as a third sequential benchmark target.
- Laravel worker-process RSS/CPU readings are intentionally reported as unavailable
  rather than incorrectly reporting the parent process only.
- Benchmarks remain opt-in and the Maven reactor is unchanged.

## Documentation & branding refresh (v0.21 snapshot)

- Added the selected FastFrame brand logo to `docs/assets/`, preserving the original artwork.
- Rebuilt the root README as a single up-to-date project overview for v0.21, including a Windows/Java 21 quickstart, module map, examples, benchmarks, deployment cautions and documentation index.
- No Java runtime behavior, Maven versions or benchmark measurements were changed by this documentation-only update.

## v0.21.0 — Performance & Memory (experimental)

- Add direct bounded UTF-8 encoding (`Json.utf8`) and remove temporary JSON strings from `Response.jsonValue`.
- Preserve JSON escaping, nesting, cyclic-input rejection and existing APIs; add Unicode parity and error JUnit tests.
- Optimize Netty binary media type checks and CORS controlled-header processing without changing policy.
- Fix benchmark artifact version resolution using Maven POM metadata instead of stale `0.9.0` FastFrame JAR name.
- Add Windows JFR profiling script, JSON allocation probe, CSV median comparison tool and regression tests.
- Retain Windows JDK 21 selection (without changing global Java 8).
- Measured only one microdiagnostic locally, not a network throughput gain; see `PERFORMANCE.md`.

# FastFrame v0.20.0 — HTTP Security

- New `framework-security` module: exact-origin CORS, bounded process-local per-IP rate limiting.
- Integrated Netty response security headers and CORS preflight. Returns 403 and 429/Retry-After.
- Windows config: `FF_CORS_ALLOWED_ORIGINS`, `FF_RATE_LIMIT_PER_MINUTE`, `FF_HSTS`.
- Added `SECURITY.md`, `PRODUCTION.md`, Maven/JUnit tests and `security-smoke.ps1`.
- GitHub dependency review and Dependabot configuration.
- Experimental: full Netty/Maven integration and production security audit remain necessary.

---

## v0.19.0-SNAPSHOT — Operations / Production Hardening (2026-10-25)

- Add lightweight `framework-observability`: bounded HTTP counters and latency histogram, readiness and optional metrics route.
- Add server-owned `X-Request-ID` and async-safe `Request.requestId()`; do not reuse incoming IDs.
- Add optional JUL access log message with no raw URL, body or auth token.
- Add `NettyServer.shutdownGracefully(Duration)` draining accepted work, with bounded force-close fallback.
- Add Windows smoke script, JUnit tests and Java-only smoke for metrics; preserve Windows JDK 21 selection.
- Disable metrics by default and reject non-loopback metrics bind in demo; OpenTelemetry/exporters deferred.
- **Test status:** Java-only smoke and previous regression checks passed; Netty/Maven integration pending.

## 0.19.0-SNAPSHOT — HTTP Feature Testing

- New optional test-scoped `framework-testing` module.
- Ephemeral-loopback real Netty HTTP client with fluent JSON/status/header assertions.
- Bearer-auth feature tests, isolated H2 fixtures, typed record factories, outbound HTTP mock.
- Added generalized strict JSON value parser for test assertions (existing bounds preserved).
- New Windows and Unix feature smoke scripts and cross-platform CI coverage.

# FastFrame v0.17.0

- Add provider registration/bootstrap lifecycle with managed teardown (`framework-application`).
- Add typed synchronous/asynchronous event subscriptions (`framework-events`).
- Add `make:provider` / `make:listener` CLI templates.
- Demo initializes an application context and publishes a startup event.
- Windows/bootstrap smoke tests and CI checks.
- Existing framework modules remain opt-in.

# v0.16.0 — Laravel-style generators and .env config

- Add `framework-config` with bounded UTF-8 `.env` parsing, immutable configuration, typed getters, and process environment priority.
- Support `FF_ENV_FILE` for storing secrets outside Windows XAMPP document roots; `.env` gitignored and `.env.example` provided.
- Demo HTTP server reads its existing port, database, JWT, timeouts and storage flags through `Config`; no breaking change to environment variables.
- Add `make:service`, `make:request`, `make:middleware`, `make:job` to the standalone Java CLI.
- Generated types implement FastFrame's existing annotation / middleware / queue APIs and are never overwritten.
- Add JUnit checks, Linux/Java 21 smoke tests, PowerShell smoke scripts, Git metadata and Windows documentation.
- No runtime reflection added to HTTP routing; existing v0.15 modules retained.
- **Not run:** full Maven build, Netty integration or native Windows test (require external Maven dependencies and Windows).

---

# v0.15.0 — Artisan-like CLI

- New standalone `framework-cli` with generated controllers, ORM record models and versioned SQL migrations.
- `route:list` reads a compact route manifest emitted at compilation by `FastFrameProcessor` (annotated routes only).
- Windows `scripts/ff.ps1` / `scripts/cli-smoke.ps1`, Unix `scripts/ff.sh` / `scripts/cli-smoke.sh`.
- Input validation, safe file creation (`CREATE_NEW`), no overwrites, no runtime controller scanning.
- New JUnit tests and CI smoke coverage; prior modules and Windows JDK 21 auto-detection retained.

---

# Changelog

## 0.14.0-SNAPSHOT — File Storage and Async HTTP Client

- Add `framework-storage`: bounded `Storage` API and `LocalDiskStorage` with portable path validation, symlink checks, atomic replace when supported, size limits, and binary file CRUD.
- Add optional `framework-storage-s3` backed by AWS SDK v2; credentials come from the caller's SDK client configuration. Storage remains opt-in.
- Add `framework-http-client` wrapping Java 21 `sendAsync`: GET, JSON POST, per-request timeouts, no automatic redirects, bounded response consumption.
- Add binary `Request.bodyBytes()` and `Response.bytes(...)` without changing JSON DTO handling. `application/octet-stream` is not decoded as UTF-8.
- Add opt-in token-protected, loopback-only storage demo routes plus Windows `storage-smoke.ps1`, tests and updated CI.
- Preserve prior JWT, Redis, Queue, ORM, migrations, Windows JDK 21 configuration and annotation processor hotfixes.
- **Not validated**: full Maven/Netty/S3 integration and native Windows execution in the artifact-generation environment.


## 0.13.0-SNAPSHOT — Background jobs and scheduling
- `framework-queue`: durable-API job envelopes, typed handler registry, retries/backoff, failed-job records, bounded in-memory queue and visibility leases.
- `framework-queue-redis`: optional Lettuce Redis adapter with atomic Lua claims, delayed jobs and stale-token fencing.
- Cron-based `JobScheduler`, process-local minute deduplication, independent worker and scheduler CLI (`queue-demo`).
- Windows PowerShell scripts, `/api/jobs/demo`, Queue smoke tests and optional Redis integration test.
- Experimental queue APIs; not production-ready; see `QUEUE_SCHEDULER.md`.

# Changelog

## v0.12.0 — Cache & Redis

- Add dependency-free `framework-cache` with bounded in-memory LRU, monotonic TTL and atomic `add`.
- Add `Cache` Laravel-style facade (`get`, `has`, `put`, `forever`, `add`, `forget`, `remember`, `rememberForever`).
- Add worker-executor `rememberAsync` for explicitly offloading blocking operations from Netty event loops.
- Add optional Lettuce-based Redis store with namespaced keys, TTL and atomic Redis `SET NX PX`.
- Add JUnit TTL, concurrency, LRU tests; opt-in Redis integration tests; Windows cache smoke script; CI coverage.
- Preserve Java 21 Windows setup, compile-time processor bootstrap fix, ORM, auth and benchmarks.
- Security: no arbitrary object deserialization; never issue FLUSHDB on shared Redis.

# FastFrame Changelog

## 0.11.0 – Auth & Authorization
- JWT HS256 bearer authentication with explicit issuer/audience/time claim validation and >=256-bit secret
- Compile-time secured routes with @Authenticated and @Authorize
- PBKDF2 password hashing, per-request identities, role/permission and resource policies
- Protected demo routes, JUnit and Windows smoke commands


## 0.10.0 — Typed ORM + migrations

- Added `framework-orm`: `Repository<T, ID>`, typed find/findOrFail, CRUD and transaction-scoped repositories.
- Added Java-record annotations `@Table`, `@Id`, `@Column` and generated `*FastFrameMapper` code (no reflective per-request mapping).
- Added `framework-migrations`: versioned `V001__*.sql` migrations, SHA-256 validation, migration history, explicit CLI.
- Added `scripts/migrate.ps1`, `scripts/migrate.sh`, `scripts/orm-smoke.ps1` and H2 demo integration.
- Included Windows/macOS/Linux benchmark and build scripts from v0.9 unchanged.
- Known limits: blocking JDBC worker-only; explicit IDs; one SQL statement per migration; no DOWN migrations or migration locks; no performance claims.

## v0.9.0 — Database Query Builder
- Add optional JDBC module: safe identifiers, prepared values, select/where/whereIn, get/first/count/exists, filtered CRUD, transactions.
- Add H2 integration tests and optional Windows demo endpoint with worker-thread offload.
- Preserve annotation processor bootstrap and JSON-order test fixes from v0.7.1.
- Keep original HTTP benchmark endpoints unchanged; no database benchmark claims.

# Changelog

## 0.7.1 — Windows JSON test correction

- Fix `GeneratedRoutesTest.generatedConstructorWiringAndRoute`: compare parsed JSON field values rather than literal key order.
- Keep runtime serializer and HTTP behavior unchanged; `Map.of` iteration order is not guaranteed.
- Retain annotation-processor bootstrap fix and configured Temurin JDK 21 for Windows.


## 0.7.1 — Annotation processor bootstrap build fix

- Disable annotation processing when compiling `framework-processor` itself (`<proc>none</proc>`).
- Keep the processor service registration and generated routes in consumer modules unchanged.
- Fix Windows/Maven build failure: `Provider dev.fastframe.processor.FastFrameProcessor not found`.


## 0.7.1 Windows local JDK selection patch

- Prefer `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` in PowerShell helper, with existing JDK detection as fallback.
- Do not modify system Java 8 or persistent environment variables.
- Update Windows setup documentation with exact local Java 21 path.

## 0.7.1 — Windows support for local development and benchmarks

- Native Windows RAM/CPU sampling via WinAPI (`GetProcessMemoryInfo`, `GetProcessTimes`) in `benchmarks/run.py`, no external Python modules.
- Added `scripts/windows-env.ps1` to select a local JDK 21 while leaving globally installed Java 8 unchanged.
- Added PowerShell entry points: `scripts/run-demo.ps1`, `benchmarks/build.ps1`, `benchmarks/preflight.ps1`, `benchmarks/run.ps1`.
- Added Windows CI build, benchmark endpoint preflight and native process-metrics checks.
- Updated Maven module versions, benchmark jar names, documentation, and Git commit message.
- Windows runtime load testing is pending native Windows execution; validated Python on Linux and compiled the Java core smoke suite.


## 0.7.0 — Lower-allocation dispatch and routing

- Added `Router.dispatchDirect()` for transport adapters: returns a direct `Response` on middleware-free synchronous routes, or a `CompletionStage<Response>` for async/middleware routes; existing `dispatchAsync()` remains backward compatible.
- Pre-indexed static URL paths and cached parameter-free route matches; preserved trie matching for parameterized routes, HEAD and OPTIONS behavior.
- Updated Netty adapter to bypass completed futures and avoid scheduling request timers for immediate responses; pending async request deadlines remain active.
- Added plain-path decoding fast path and skipped UTF-8 decoder instantiation for empty bodies; retained decoding for query strings and percent-escaped paths.
- Added core and Netty regression tests for synchronous, async, middleware and encoded/query behavior, plus Java 21 smoke coverage.
- Kept macOS/Linux benchmark support and the Micronaut annotation-processor classpath fix from v0.6.
- No throughput, latency or RSS performance improvement is claimed until benchmark results are measured on real hardware.

## v0.6.0 — macOS benchmark compatibility patch

- Fix `benchmarks/run.py` refusing to run outside Linux.
- Read process RSS (MiB) and accumulated CPU time on macOS using `ps`,
  retaining the original low-overhead Linux `/proc` backend.
- Track process-metrics backend in benchmark environment metadata.
- Add tests for macOS `ps` output, CPU time parsing and current-process sampling.
- Document macOS setup, different sampling intervals and cross-OS caveats.
- Add a GitHub Actions macOS job for metric collection regression tests.
- Preserve the Micronaut `NullMarked` annotation processor classpath fix.

# CHANGELOG

## [0.6.0 build patch] — Micronaut annotation processor classpath (2026-10-24)

- Added explicit `micronaut-core` to the Micronaut compiler annotation processor classpath, fixing the missing `io.micronaut.core.annotation.NullMarked` class reported during `benchmark-micronaut` compilation.
- Enabled BOM-managed transitive versions for Maven annotation processors.
- Documented the build error and retry commands. No framework runtime code or benchmark workload changed.


## [0.6.0] — performance benchmark harness (2026-10-23)

- Added independent benchmark HTTP services for FastFrame and Micronaut with comparable request/response semantics.
- Added four HTTP/1.1 workloads: plain, JSON serialization, dynamic route, and POST JSON DTO echo.
- Added `hey` runner with HTTP preflight, per-endpoint warmup, alternating sequential server runs, per-request CSV samples, HTTP status accounting, and p50/p95/p99 analysis.
- Added Linux process RSS/CPU sampling, reproducibility metadata, and median-based result summarizer.
- Added smoke tests for FastFrame generated benchmark routes and CSV analysis; CI builds and performs HTTP preflight for both benchmark apps.
- No benchmark numbers are included because full external dependency build and load testing could not run in the artifact-generation environment.

All notable changes to FastFrame are documented here. This project is a prototype and has not reached a stable release.

## [0.5.0] - 2026-10-23

### Added
- Compile-time `@Body` JSON request binding for public top-level Java `record` DTOs, with direct generated constructor calls (no runtime reflection for DTO binding).
- Record component annotations `@Required`, `@Size`, `@Min`, `@Max` for input validation.
- `JsonBody` strict bounded parser with duplicate-key rejection, JSON syntax checks, UTF-16 validation, max depth/value limits and disallowed unknown DTO fields.
- Aggregated `ValidationException` with per-field messages; HTTP adapter maps this to JSON HTTP 422.
- JSON content-type enforcement (`application/json`, UTF-8 only) with HTTP 415; malformed JSON gives HTTP 400.
- Strict UTF-8 decoding at the HTTP transport boundary; malformed byte sequences yield HTTP 400.
- Demo `CreateUserRequest` DTO and creation endpoint; Java smoke coverage, processor negative tests, JUnit parser/Netty mapping tests.

### Limitations
- DTO binding accepts only public non-generic top-level records with `String`, `int/Integer`, `long/Long`, or `boolean/Boolean` components. It does not support nested records, collections, defaults, custom validators, multipart or arbitrary POJO serialization.
- JSON parser is intentionally small, new code requiring fuzzing and security review. It is not a claim of superiority over Jackson or Micronaut.
- Network/Netty tests have not been run here (Maven unavailable). No benchmark evidence or production hardening yet.

## [0.4.0] - 2026-10-23

### Added
- Server-wide in-flight request admission via a shared semaphore; overload returns HTTP 503 with `Retry-After`.
- Strict per-channel response queue cap in addition to Netty read backpressure.
- Configurable request timeout with HTTP 504 and cooperative request cancellation on timeout/disconnect.
- `Response.jsonValue` with bounded, escaping JSON encoder for maps, arrays, iterables and basic scalar types.
- `HttpException` for intentionally public 4xx/5xx errors; centralized safe error mapping for unexpected failures.
- Netty `EmbeddedChannel` JUnit tests for overload, timeout, disconnect cleanup and error handling.
- New Java-only v0.4 smoke coverage for JSON, cancellation and header injection.
- Configurable options from demo environment variables (`MAX_IN_FLIGHT`, `MAX_PIPELINED`, `MAX_REQUEST_BYTES`, `REQUEST_TIMEOUT_MS`).

### Changed
- Demo controllers now generate JSON via `Response.jsonValue` instead of manual concatenation.
- Header values reject control characters; late async completions do not overwrite timed-out responses.

### Known limitations
- Deadlines are cooperative and cannot preempt blocking work on a Netty event loop.
- No cap on open TCP connections or total buffered bytes; not production-ready.
- No raw JSON validation, JSON parsing, arbitrary POJO mapping, HTTP/2, TLS or observability.
- Netty/JUnit integration tests not executed here (Maven unavailable); no performance claims.

## [0.3.0] - 2026-10-23

### Added
- `framework-processor` annotation processor generates `dev.fastframe.generated.FastFrameRoutes`.
- `@Controller`, `@Get`, `@Post`, `@Put`, `@Delete` for declarative routing.
- `@Singleton` and constructor `@Inject` generate singleton wiring (no request-time reflection).
- Path/query binding for `String`, `int` and `long`; malformed numeric inputs produce HTTP 400.
- Sync and async annotated controller actions with `Response` / `CompletionStage<Response>`.
- Compile-time failures for unsupported controllers, missing dependencies, DI cycles, duplicate annotated routes and invalid return types.
- Generated-route JUnit tests, dependency-free Java smoke / processor negative tests, GitHub Actions CI.

### Limitations
- Java 21 prototype; only public top-level concrete beans and public constructors; singleton scope per route registration.
- Cross-module bean scanning, interfaces/qualifiers, validation, JSON object mapping and HTTP deadlines are not implemented.
- No measured performance claims; Maven/Netty integration was not executable in this environment.

## [0.2.0] - 2026-10-23

### Added
- Asynchronous HTTP route handlers built around `CompletionStage<Response>`.
- Global middleware and route-group middleware.
- `HEAD` and `OPTIONS` behavior, with 403/404/405/500/503 response paths.
- In-order HTTP/1.1 pipelined responses and per-connection read throttling.
- Core smoke-test script and routing/HTTP adapter tests.

### Changed
- Netty adapter dispatches asynchronous handlers without blocking the event loop on controller completion.

### Known limitations
- Prototype only; no comparative Micronaut benchmarks or production security hardening.
- Blocking I/O must be dispatched to an appropriate bounded executor.
- No built-in DI code generation, TLS, HTTP/2, deadlines, or in-flight cancellation.

### Windows processor hotfix (2026-10-24)

- Delay processor service registration until `process-classes` to prevent self-loading during compilation.
- Add root verification to Windows launcher and ship flat extraction ZIP.

### Test-only hotfix (v0.22.0-SNAPSHOT)

- Correct `SecurityPolicyTest.windowRolloverAndRetryAfter` to account for the newly acquired A bucket at t=11s and B remaining active until t=13s.
- No runtime security changes.

### v0.22 — Benchmark Suite 6: 60-minute Soak (2026-10-27)

- Added `benchmarks/soak.py` with k6 fixed-arrival 3600s per framework, 1-second validation mode, guaranteed process cleanup and recoverable partial results.
- Added `psutil` parent+descendant monitoring for RSS and CPU, including Laravel Octane/FrankenPHP workers.
- Added optional JDK `jstat -gc` sampling for Java heap and cumulative GC collections/time. PHP JVM-only fields are N/A.
- Added independent HTTP health-probe error series, plus final k6 HTTP errors, dropped iterations, RPS and p99.
- Added self-contained offline HTML resource charts, CSV/JSON/Markdown reports, macOS/Linux and PowerShell wrappers, regression tests and `benchmarks/SOAK.md`.
- Preserved Concurrency Scaling, Target RPS/p99, JSON Sizes, Database & Redis, Async I/O & JWT benchmark modes.
