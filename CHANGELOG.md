# Changelog

All notable changes to FastFrame are documented here. This project is a prototype and has not reached a stable release.

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
