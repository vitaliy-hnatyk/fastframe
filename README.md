# FastFrame v0.7.1 — Laravel-inspired Java + Netty framework (prototype)

Experimental Java 21 / Netty 4.2 framework with compile-time routing, singleton constructor DI, async middleware/controllers, and **JSON request binding to Java record DTOs with field validation**. The processor generates Java source so there is no runtime reflection to find controllers or construct request DTOs.

> **Not production-ready.** The new JSON parser needs fuzzing/security review; HTTP/1.1 only, no built-in TLS, authentication, metrics, graceful body streaming, full content negotiation, or established Micronaut performance results. Never deploy this demo as a publicly exposed service.

## Windows: JSON test correction

If `GeneratedRoutesTest.generatedConstructorWiringAndRoute` fails because the expected JSON fields are in a different order, replace the test file in `demo-app/src/test/java/dev/fastframe/demo/GeneratedRoutesTest.java` with the updated version. It now parses JSON and asserts field values independently of their order.

From PowerShell, run `Set-ExecutionPolicy -Scope Process Bypass -Force` (only for reviewed scripts, if needed), then `./scripts/run-demo.ps1`. Alternatively: `mvn -B -ntp -pl demo-app -am clean verify`.

## Windows: annotation processor bootstrap build fix

If `framework-processor` fails with
`Provider dev.fastframe.processor.FastFrameProcessor not found`, update
`framework-processor/pom.xml` to include `<proc>none</proc>` **only for this
module**. This prevents javac from trying to load the uncompiled processor
class from `META-INF/services` during its own compilation; processor discovery
in `demo-app` and `benchmarks/fastframe-app` is unaffected.

From the project root in PowerShell:

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\run-demo.ps1
```

The script selects your configured JDK 21 without changing the system Java 8.
For a build-only check:

```powershell
mvn -B -ntp -pl demo-app -am clean package
```

## New in v0.7.1: Windows development + benchmarking

The Java framework and Netty server now come with **Windows PowerShell 5.1+/PowerShell 7** scripts. You can keep **Java 8 installed globally** and use JDK 21 only in the PowerShell session running FastFrame. No permanent PATH changes are made.

On Windows (from the project root), after installing **Temurin JDK 21**, **Maven 3.9+**, and **Python 3.10+**:

```powershell
# Build + start the demo on localhost:8080
.\scripts\run-demo.ps1
# In another terminal:
curl.exe http://localhost:8080/api/health
```

To compare FastFrame with Micronaut on Windows, install [hey](https://github.com/rakyll/hey) and run:

```powershell
# Build both servers, check HTTP endpoints, measure load (hey required)
.\benchmarks\run.ps1 -Duration 20s -Warmup 5s -Concurrency 64 -Repeats 3
# Or build and check separately:
.\benchmarks\build.ps1
.\benchmarks\preflight.ps1
```

See **[WINDOWS.md](WINDOWS.md)** for installation, PowerShell execution policy, `hey`, Java 8 coexistence, and Windows-specific troubleshooting. Results are saved in `benchmarks/results/<timestamp>/` as CSV/JSON. Windows process memory/CPU are read directly through WinAPI (working set + process times), without `psutil` or `/proc`.

## New in v0.7: Lower-allocation HTTP request path

This release changes the internal fast path without changing annotated controller syntax:

- **Direct synchronous dispatch:** a controller registered with `Router.add`, `get`, `post`, etc. returns its `Response` to the Netty adapter without allocating a `CompletableFuture` or running a `whenComplete` callback. Async routes and middleware still use `CompletionStage<Response>` and retain timeouts.
- **No needless timer scheduling:** synchronous responses and already-completed stages do not schedule an event-loop timeout. Truly pending async requests still get HTTP 504 and cooperative cancellation.
- **Exact static routes:** an exact-match index avoids walking the trie and allocating a `Match` for fixed URL paths; parameterized routes still use the trie and static-route precedence is preserved.
- **Less URI/body decoding:** a plain URL path skips `QueryStringDecoder`; empty HTTP bodies skip UTF-8 decoder allocation. Requests containing percent escapes or queries still use Netty's decoder, and nonempty request bodies still undergo strict UTF-8 validation.

These are engineering changes, **not an established performance gain**. Re-run the benchmark from v0.6 and v0.7 on the same machine before drawing conclusions. Memory and p99 are at least as important as RPS. Full Netty/Micronaut Maven integration tests were not executed in the ZIP creation environment.

## New in v0.6: Benchmark suite vs Micronaut

Four comparable benchmark endpoints are provided by two **standalone** applications:
`benchmarks/fastframe-app` and `benchmarks/micronaut-app`. The latter uses
Micronaut Platform 4.10.21 (Core 4.10.31) and Micronaut Serde, while the former
uses FastFrame on Netty 4.2.19.Final. Run:

```bash
bash benchmarks/build.sh
python3 benchmarks/run.py --duration 20s --warmup 5s --concurrency 64 --repeats 3
python3 benchmarks/summarize.py benchmarks/results/<timestamp>/results.csv
```

The runner supports **Windows, Linux and macOS** and requires Java 21, Maven, Python 3.10+, and hey. It starts one server
at a time, checks endpoint correctness, warms up, then captures RPS, latency,
RSS, CPU and HTTP errors. **No actual Micronaut comparison figures have been
measured in the ZIP creation environment.** Details and methodology:
[`benchmarks/README.md`](benchmarks/README.md).

On macOS, the benchmark uses BSD `ps` for process RSS and CPU time, instead of
Linux `/proc`. Install prerequisites with `brew install maven hey` (and Java 21 if
needed). Read [`benchmarks/README.md`](benchmarks/README.md) for details.

For FastFrame benchmark routing and CSV parser smoke tests (no Maven required):

```bash
bash scripts/benchmark-smoke.sh
```

## Requirements

- JDK 21+
- Maven 3.9+

## Build and run

```bash
mvn -pl demo-app -am clean verify
mvn -pl demo-app -am package
java -jar demo-app/target/demo-app-0.7.1-SNAPSHOT.jar
```

Without Maven, run the **Java-only** core/processor smoke test:

```bash
bash scripts/core-smoke.sh
```

It compiles `framework-core`, `framework-processor` and demo classes directly with Java 21, generates `FastFrameRoutes` and checks routing, DI, JSON DTO binding, validation, error conditions and processor failures. **It does not compile or exercise Netty**. JUnit Netty tests and the full Maven build are provided for execution where Maven dependencies are available, but were not executed in the build environment used to produce this ZIP.

## JSON DTO example (new in v0.5)

```java
// demo-app/.../dto/CreateUserRequest.java
public record CreateUserRequest(
    @Required @Size(min = 2, max = 80) String name,
    @Required @Size(min = 5, max = 254) String email,
    @Min(13) @Max(120) int age
) {}

// demo-app/.../controllers/UserController.java
@Controller("/api/users")
public final class UserController {
    @Post
    public Response store(@Body CreateUserRequest dto) {
        return Response.jsonValue(Map.of(
            "name", dto.name(), "email", dto.email(),
            "age", dto.age(), "created", true
        )).status(201);
    }
}
```

The annotation processor generates direct calls equivalent to:

```java
JsonBody json = JsonBody.parse(request);
String name = json.string("name", true, 2, 80);
String email = json.string("email", true, 5, 254);
int age = java.util.Objects.requireNonNullElse(json.integer("age", true, 13L, 120L), 0);
json.finish("name", "email", "age");
CreateUserRequest dto = new CreateUserRequest(name, email, age);
```

**No reflection on the DTO binding path.** Binding still creates an object tree (`Map`/`List`) for the parsed JSON and therefore has allocation overhead. This API is not yet a streaming zero-allocation JSON codec.

### Example requests

```bash
curl -i http://localhost:8080/api/health
curl -i -X POST http://localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.org","age":27}'

# Field-level validation: HTTP 422
curl -i -X POST http://localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"A","email":5,"age":7}'

# Invalid JSON syntax: HTTP 400
curl -i -X POST http://localhost:8080/api/users \
  -H 'Content-Type: application/json' -d '{"age":1,,}'

# Unsupported content type: HTTP 415
curl -i -X POST http://localhost:8080/api/users \
  -H 'Content-Type: text/plain' -d '{}'
```

Successful creation responds with `201` and JSON body containing the submitted DTO fields. Invalid data produces a JSON error object, for example:

```json
{
  "error": {
    "code": "validation_failed",
    "message": "Validation failed",
    "fields": {
      "name": ["length must be between 2 and 80"],
      "email": ["must be a string"],
      "age": ["must be between 13 and 120"]
    }
  }
}
```

Field ordering in error JSON is not guaranteed.

## DTO binding rules and constraints

- `@Body` must annotate **at most one** controller argument; it must be a `public` top-level, non-generic Java `record`.
- Allowed record component types: `String`, `int`, `Integer`, `long`, `Long`, `boolean`, `Boolean`. All primitive components are implicitly required.
- `@Required` (non-null), `@Size(min, max)` (String UTF-16 length), `@Min(value)` and `@Max(value)` (integer range).
- Missing nullable fields may map to `null`; primitives cannot. Unknown fields are **rejected** with HTTP 422 instead of silently ignored.
- Valid `application/json` is required, optionally with `charset=utf-8`; other encodings/content types produce HTTP 415. JSON syntax errors, duplicate object keys, invalid surrogate pairs, non-object roots and excessive nesting produce HTTP 400.
- Built-in limits in `JsonBody`: input <= 65,536 Java characters (in addition to Netty's byte limit), 32 nesting levels, 2,048 JSON values, maximum 64-character numeric tokens.
- Netty's request payload limit is configurable and defaults to 65,536 **bytes**, aggregated before parsing; invalid UTF-8 sequences are rejected with HTTP 400.
- The validator currently accumulates simple type/length/range issues; it does not validate email format or provide arbitrary custom validators.
- `Response.jsonValue` still supports basic Java collections/primitives, **not automatic arbitrary record/POJO serialization**. The controller demonstrates `Map.of(...)` responses.

## Framework architecture

```text
framework-core/       Router, HTTP DTO bindings, validation, DI/HTTP annotations
framework-processor/  Compile-time route/DI/DTO Java code generator
framework-netty/      Netty transport, admission limits, deadlines, response/error mapping
 demo-app/            Annotated controller, CreateUserRequest, sample service
scripts/              Java smoke and negative-compile tests (Maven-free)
.github/workflows/    Full Maven/JUnit and smoke CI configuration
```

Async controllers use `CompletionStage<Response>`; middleware is registered before starting the server. Route registration constructs and shares singleton controllers, so they **must be thread-safe**. The Netty event loop must not execute blocking I/O: use nonblocking clients or a bounded worker executor. Async controller cancellation remains cooperative.

## Server configuration

| Environment variable | Default | Description |
| --- | ---: | --- |
| `PORT` | `8080` | TCP port |
| `MAX_IN_FLIGHT` | `256` | Shared concurrent request limit (503 when exceeded) |
| `MAX_PIPELINED` | `64` | Per-channel queued responses |
| `MAX_REQUEST_BYTES` | `65536` | Max aggregated HTTP request bytes |
| `REQUEST_TIMEOUT_MS` | `10000` | Async request timeout in milliseconds (504) |

The in-flight semaphore does **not** cap open TCP connections, socket bytes, or overall memory. Request timeouts cannot interrupt blocking synchronous code. HTTP/1.1 pipelining retains response ordering.

## Git workflow

The ZIP is a clean source snapshot with no `.git` history.

**New repository:**

```bash
git init
git add .
git commit -F COMMIT_MESSAGE.txt
```

**Existing FastFrame repository:** copy all ZIP contents over the existing project (leave `.git` unchanged), then run:

```bash
git status
git add .
git commit -F COMMIT_MESSAGE.txt
```

## Next milestone — v0.8

Prioritize full macOS/Linux Maven integration tests, JFR allocated-bytes/request profiling, and a concurrency matrix (16/64/128/256) against Micronaut before pursuing more low-level Netty changes. Do not claim FastFrame is faster or uses less RAM until identical-load benchmarks with warmup, p99 latency, RPS, alloc/op and RSS are recorded.
