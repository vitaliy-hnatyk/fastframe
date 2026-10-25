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
