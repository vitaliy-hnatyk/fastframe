# FastFrame v0.22 — TLS, HTTP/2, WebSocket, SSE, HTTP/3 at the edge

**Status: development preview.** The original HTTP/1.1 server remains the default. New Netty handlers have not passed a complete Maven compile or live TLS/HTTP2/WebSocket integration test in the current environment. Do not expose these features publicly before confirming builds, authentication, and load/resource limits.

## Protocols

| Protocol | Implementation | Enablement |
|---|---|---|
| HTTP/1.1 | Existing Netty pipeline | Default, no configuration changes |
| HTTPS/TLS 1.3 | `TlsSettings` + Netty SSL handler | `BENCH_TLS_CERT`, `BENCH_TLS_KEY` |
| HTTP/2 TLS ALPN | `Http2FrameCodec`, `Http2MultiplexHandler` and stream router | `BENCH_HTTP2=true` with TLS PEMs |
| HTTP/2 cleartext | `NettyTransport.h2c()` prior-knowledge mode | `BENCH_H2C=true`; separate listener |
| HTTP/3 QUIC | **Caddy reverse-proxy termination**, FastFrame upstream HTTP/1.1 | `deploy/caddy/Caddyfile`, TCP+UDP 443 |
| WebSocket RFC6455 | `Response.websocket`, `WebSocketFrames`, routes/middleware | HTTP/1.1 `/bench/ws` example |
| SSE | `Response.sse`, `SseChannel` routes/middleware | `/bench/events` example; no unlimited buffering |

Native Java HTTP/3 is **not implemented** in this patch. It will require a separate native Netty QUIC UDP adapter, platform-specific testing and stream-reset/flow-control hardening. Caddy provides actual client-facing HTTP/3 without requiring that Java transport.

## Build and run FastFrame benchmark app

```bash
mvn -B -ntp -pl benchmarks/fastframe-app -am clean package
java -jar benchmarks/fastframe-app/target/benchmark-fastframe-0.22.0-SNAPSHOT.jar
# Second shell:
curl -N http://127.0.0.1:18080/bench/events
# WebSocket: use any RFC6455 echo client at ws://127.0.0.1:18080/bench/ws
```

## Enable TLS 1.3 + HTTP/2 on the native FastFrame listener

Local development only:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout /tmp/ff.key -out /tmp/ff.crt -subj '/CN=localhost'
BENCH_TLS_CERT=/tmp/ff.crt BENCH_TLS_KEY=/tmp/ff.key \
  BENCH_HTTP2=true PORT=18443 \
  java -jar benchmarks/fastframe-app/target/benchmark-fastframe-0.22.0-SNAPSHOT.jar

curl -k --http1.1 -i https://localhost:18443/bench/plain
curl -k --http2 -i https://localhost:18443/bench/plain
```

The certificate must match the host name when performing real TLS verification. The `-k` flag is **development-only**. For h2c prior knowledge without TLS use `BENCH_H2C=true PORT=18080` and `curl --http2-prior-knowledge http://127.0.0.1:18080/bench/plain`. That listener does not also accept HTTP/1.1.

## HTTP/3 with Caddy

Configure `deploy/caddy/Caddyfile` with a real DNS name. Run FastFrame on private `127.0.0.1:8080`. Caddy handles HTTPS, HTTP/2 and HTTP/3 on TCP 443 + UDP 443 and forwards to FastFrame HTTP/1.1. Verify with an HTTP/3-enabled curl: `curl -V` needs `HTTP3` support, then `curl --http3-only https://api.example.com/bench/plain`. **This exercises HTTP/3 at Caddy, not native HTTP/3 inside FastFrame.**

## Router API

```java
Router router = new Router();
router.get("/events", request -> Response.sse(sink -> {
    sink.onClose(() -> unsubscribeFromEventBus()); // example cleanup callback
    sink.send("ready", "connected");
}));
router.get("/ws", request -> Response.websocket(text ->
    java.util.concurrent.CompletableFuture.completedFuture("echo:" + text)));
```

Register authentication middleware before opening either stream. The event source must not block Netty's event loop. SSE `send` returns `false` when a channel is closed/backpressured; it is not a durable message queue. WebSocket supports text, binary callbacks, ping/pong, close and a maximum message size through `NettyOptions.maxRequestBytes()`. WebSocket upgrade applies to HTTP/1.1, not HTTP/2 extended CONNECT. Large-scale SSE/WS subscriptions and slow-client backpressure require further integration testing.

## Benchmark all three backends over protocol frontends

`benchmarks/protocols.py` generates identical batches of requests with curl and **rejects HTTP version fallback**. For a three-framework comparison give each app its own TLS virtual host (for example Caddy instances or separate backend proxies):

```bash
python3 benchmarks/protocols.py \
  --target fastframe=https://fastframe.example.com/bench/plain \
  --target micronaut=https://micronaut.example.com/bench/plain \
  --target laravel=https://laravel.example.com/bench/plain \
  --protocols h1 h2 h3 --requests 300 --concurrency 16
```

Output: `benchmarks/results/protocols-*/results.json`, `results.csv`, `report.html`. Each result records the negotiated protocol and invalidates mixed/fallback/failed responses. `curl --parallel` includes process overhead, connection establishment and TLS handshake; **do not interpret RPS as the maximum capacity of a server**. Use a calibrated external load generator and repeat runs for capacity comparisons.

## Verification and limitations

- New Python tests: protocol validation, fallback rejection and standalone offline HTML report.
- Existing 126 Python tests and `scripts/benchmark-smoke.sh` passed on Linux. Java 21 verified response-contract compilation separately.
- Full Maven, native Netty TLS/h2 integration, WebSocket and SSE integration tests, Windows and macOS integration **have not been executed** here.
- Native QUIC/HTTP3, HTTP/2 extended CONNECT, WebTransport, and gRPC are **not supplied** by the application transport. Use Caddy for real HTTP/3 deployment until native QUIC is independently implemented and hardened.
