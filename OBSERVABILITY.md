# FastFrame v0.19 — Operations and observability

Java 21 + Netty HTTP/1.1. This is a **hardening milestone**, not a production certification.

## New endpoints

| Endpoint | HTTP | Purpose |
|---|---|---|
| `GET /_health/live` | 200 | Process / HTTP stack is alive |
| `GET /_health/ready` | 200 / 503 | Ready after bind; not ready before bind or during shutdown |
| `GET /_metrics` | 200 | Prometheus text exposition; **disabled by default** |

`/_health/ready` does **not** inspect the database, Redis, queues or external services yet. It reports only server lifecycle readiness.

Enable metrics in a **private** deployment. For v0.19 the demo refuses to expose the endpoint when `FF_HOST` is not localhost; **do not publish loopback endpoints through Apache/XAMPP reverse proxies** without adding a secure authentication layer. Metrics are off by default to reduce attack surface. The HTTP listener does not implement TLS; use trusted ingress/TLS termination in deployment.

PowerShell:

```powershell
cd C:\xampp\www\fastframe
$env:FF_HOST = '127.0.0.1'
$env:FF_ENABLE_METRICS = 'true'
$env:FF_ACCESS_LOG = 'true'
.\scripts\run-demo.ps1
```

In another PowerShell:

```powershell
curl.exe -i http://127.0.0.1:8080/_health/live
curl.exe -i http://127.0.0.1:8080/_health/ready
curl.exe http://127.0.0.1:8080/_metrics
curl.exe -i http://127.0.0.1:8080/api/health
```

Every response processed by Netty has a **server-generated** `X-Request-ID` header. The incoming header of the same name is **not trusted or reused**; controllers can read `request.requestId()`. Not an OpenTelemetry trace ID.

The `FF_ACCESS_LOG=true` switch enables JUL request completion logs with a JSON message holding `event`, `request_id`, `method`, `status`, `duration_ms`. Standard JUL formatting can add a text prefix, so default output is **not guaranteed to be JSONL**. The log intentionally excludes URL/path/query, bodies and authentication headers. **Default: off** to avoid logging overhead.

## Prometheus data

- `fastframe_http_requests_received_total`
- `fastframe_http_requests_completed_total`
- `fastframe_http_requests_aborted_total`
- `fastframe_http_requests_active`
- `fastframe_http_responses_total{status_class="2xx"}` (1xx–5xx only)
- `fastframe_http_handler_duration_seconds` histogram with bounded fixed buckets

Totals count decoded requests that reached the handler. Requests dropped by HTTP parser or extreme pipelining before handler admission are not necessarily counted. `completed` measures completion of a **handler response**, not necessarily delivery to the client socket. On disconnect, unfinished requests are counted as `aborted`; cancellations are cooperative. No user-controllable path or request ID labels are included. Metrics are in-memory and reset on restart.

## Graceful shutdown

`NettyServer.shutdownGracefully(Duration)`:

1. Marks readiness false.
2. Closes listening socket (stops accepting new connections).
3. Disables AutoRead on existing connections, lets already-accepted in-flight responses finish in HTTP/1.1 order; closes idle keep-alive connections.
4. Waits up to the provided deadline, then force-closes remaining connections. Handler cancellation remains cooperative.

Demo installs a JVM shutdown hook with **5-second** grace period. Do not call this method from a Netty event-loop thread. This does not guarantee completion of external queue jobs, DB commits, or files in progress; they have their own lifecycles.

## Build & tests (Java 21)

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\observability-smoke.ps1
.\scripts\run-demo.ps1
```

```bash
bash scripts/observability-smoke.sh
mvn -B -ntp -pl framework-observability,framework-netty,demo-app -am clean verify
```

CI adds unit tests for metrics/readiness, Netty embedded request ID and graceful pipelining, and a localhost integration test for shutdown of an in-flight HTTP request. These need Maven and real Netty jars; the Java-only smoke test does **not** exercise Netty.

## Not yet available

OpenTelemetry exporters/spans, distributed tracing, authenticated public metrics, TLS termination, health checks of external dependencies, and runtime performance guarantees. Re-run benchmarks against v0.18 before claiming an improvement: request ID and metrics have a cost.
