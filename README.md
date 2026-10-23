# FastFrame v0.2.0 (working title)

A **Laravel-inspired Java HTTP framework prototype**, built on Netty 4.2.19.Final and Java 21. This release adds **asynchronous controllers** and **global/group middleware** to the original prototype. This is experimental software, **not production-ready** and **not yet benchmarked against Micronaut**.

## Prerequisites

- Java 21+
- Maven 3.9+

## Build & run

```bash
mvn -pl demo-app -am clean package
java -jar demo-app/target/demo-app-0.2.0-SNAPSHOT.jar
```

Custom port: `PORT=9090 java -jar demo-app/target/demo-app-0.2.0-SNAPSHOT.jar`

Try the endpoints:

```bash
curl -i http://localhost:8080/
curl -i http://localhost:8080/api/health
curl -i http://localhost:8080/api/users/42
curl -i -X POST http://localhost:8080/api/users
curl -i http://localhost:8080/api/async-demo
curl -i -H 'X-Demo-Block: true' http://localhost:8080/api/health  # 403
curl -i -X OPTIONS http://localhost:8080/api/users/42       # 204 + Allow
curl -I http://localhost:8080/api/users/42                  # HEAD
```

`mvn test` runs unit tests of the router and HTTP adapter tests using Netty EmbeddedChannel.

### Java core smoke test without Maven

For environments that have a JDK but not Maven, `bash scripts/core-smoke.sh` compiles and exercises **framework-core and the demo route registration only**. It does **not** compile or test the Netty module. It uses no external dependencies.

## Project layout

```text
framework-core/    Router, Request/Response, sync/async handlers, middleware (no Netty imports)
framework-netty/   HTTP/1.1 server, asynchronous Netty adapter, pipelining-safe reply ordering
 demo-app/         Laravel-style routes, controllers, async demo, bounded worker executor
scripts/           dependency-free Java core smoke test
```

## Laravel-style routes

```java
Router router = new Router();
router.use((request, next) -> next.proceed(request)
        .thenApply(response -> response.header("X-App", "FastFrame")));

router.group("/api", api -> {
    api.middleware((request, next) -> {
        if (request.header("x-access-denied") != null) {
            return CompletableFuture.completedFuture(Response.text("Forbidden").status(403));
        }
        return next.proceed(request);
    });
    api.get("/users/{id}", users::show);
    api.post("/users", users::store);
    api.getAsync("/users/{id}/details", request -> fetchUserAsync(request.routeParam("id"))
            .thenApply(userJson -> Response.json(userJson)));
});
```

`fetchUserAsync` is just an illustrative function name; the sample app includes the working `/api/async-demo` endpoint. Sync handlers return `Response`; async handlers return `CompletionStage<Response>`. **Neither should block Netty event-loop threads**. If a library uses blocking I/O, explicitly offload to a **bounded** executor, as shown in the demo. Saturation is translated to HTTP `503` if it causes `RejectedExecutionException`.

## HTTP behavior & implementation details

- HTTP/1.1 only; GET, POST, PUT, DELETE; direct async variants (`getAsync`, etc.); route parameters; static routes take priority.
- HEAD falls back to a GET handler if not explicitly registered; Netty suppresses response body but reports GET's `Content-Length`.
- OPTIONS returns HTTP 204 and `Allow` on known resources (explicit OPTIONS route takes priority). Unsupported methods on known paths return 405; unknown paths return 404.
- Global middleware must be installed **before** registering routes. Group middleware must be installed before the group's first route. Handler chains are built **once at registration**, rather than on every dispatch.
- Async completions are marshalled onto the Netty event loop, and responses for HTTP pipelining are sent in request order, even if futures finish out of order.
- Read throttling via Netty `autoRead` when 64 responses are outstanding, resume at 32. This is **per-connection** and **not an absolute memory/concurrency limit**. No total inflight request budget or request deadline yet.
- Disconnects clear the per-channel response queue, but **do not cancel running controller operations**. Add cooperative cancellation and timeouts in a later release.
- Request bodies are fully aggregated in memory up to **64 KiB** and currently decoded as UTF-8 text.
- `Response.json(String)` accepts **already serialized** JSON; no object JSON mapper is included.
- `Router.dispatch(Request)` still exists for compatibility and tests but **blocks** for async handlers. Netty uses **only** `Router.dispatchAsync(Request)`.

## Production limitations

No built-in authentication, authorization, TLS, HTTP/2 or HTTP/3, secure session handling, database connection management, generated dependency injection, validation, request timeouts, observability, graceful in-flight shutdown, or distributed rate limiting. Terminate TLS at a trusted proxy if experimenting outside localhost. Never expose the demo app directly to the internet.

Router configuration is startup-only; do not register or mutate routes once serving requests. Server uses standard Java NIO transport by default, not platform-native epoll/io_uring.

## Git: first commit

The downloaded ZIP does **not** include a `.git` directory, existing Git history, or a configured commit author. Initialize a repository after extracting it:

```bash
cd netty-laravel-starter
git init
git add .
git status
git commit -F COMMIT_MESSAGE.txt
```

The proposed commit subject is **`feat: bootstrap FastFrame v0.2 on Netty`**, with a detailed description in [`COMMIT_MESSAGE.txt`](COMMIT_MESSAGE.txt). If Git asks for your name/email, set **your own** identity (the example values below are placeholders):

```bash
git config user.name "Your Name"
git config user.email "you@example.com"
```

To push the repository after creating an empty repo on GitHub/GitLab:

```bash
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git
git push -u origin main
```

Replace the remote with **your actual repository URL**. Use [`CHANGELOG.md`](CHANGELOG.md) for version notes and `.gitignore` to keep generated Maven/IDE files out of commits.

## Next milestones

1. Enforce total in-flight budgets + per-route deadlines and cooperative cancellation.
2. Introduce **compile-time DI** and compile-time route registration via an annotation processor.
3. JSON codec abstraction, typed request binding, validation, error mapping, structured logs.
4. TLS, HTTP/2, graceful shutdown, security hardening, integration tests.
5. Reproducible **same-workload** benchmarks vs Micronaut: RPS, p95/p99, RSS, CPU, alloc/op, GC.

**Performance is not measured**. A clean Netty backend alone does not prove a speed advantage over Micronaut.
