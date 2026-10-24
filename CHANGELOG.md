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
