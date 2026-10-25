# FastFrame v0.19 Testing (Java 21)

The **test-scoped** `framework-testing` module provides network-level HTTP feature testing,
JSON assertions, isolated H2 test databases, typed factories and an outbound HTTP mock.
It uses the **real Netty server** on an ephemeral `127.0.0.1` port, exercising the HTTP codec,
request parsing, middleware, routing and error mapping. `TestClient` is **not** a performance
benchmark and should not be used to publish RPS claims.

## Windows PowerShell

Use JDK 21 without replacing the system's Java 8:

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\testing-smoke.ps1
```

`testing-smoke.ps1` runs `mvn -pl framework-testing,demo-app -am clean verify`,
including `FeatureAuthTest` against annotated routes and the H2 tests.
Linux/macOS: `bash scripts/testing-smoke.sh`.

## Feature API

Declare only in the **test** dependencies of your application:

```xml
<dependency>
  <groupId>dev.fastframe</groupId>
  <artifactId>framework-testing</artifactId>
  <version>${project.version}</version>
  <scope>test</scope>
</dependency>
```

```java
@Test void users() {
    var router = new Router();
    router.get("/api/users", r -> Response.jsonValue(
        Map.of("users", List.of(Map.of("id", 1, "name", "Ada")))));
    try (TestClient app = TestClient.start(router)) {
        app.get("/api/users").assertStatus(200)
            .assertJsonPath("$.users[0].id", 1)
            .assertJson("{\"users\":[{\"name\":\"Ada\",\"id\":1}]}");
    }
}
```

`TestClient` accepts `get`, `postJson`, `putJson`, `delete`, `withHeader`, and
`withBearerToken`. Sessions are immutable, so authorization headers cannot leak between tests.
`TestResponse` provides `assertStatus`, `assertHeader`, `assertBody`,
`assertJson`, and basic `assertJsonPath` (`$.user.name`, `$.users[0].id`). JSON
comparisons ignore object key ordering and compare numeric values by magnitude.
Use a signed short-lived test JWT created with `JwtHmac256` to test role/permission guards;
this exercises actual middleware, unlike injecting an identity directly into a `Request`.

## RefreshDatabase-style test isolation

```java
try (var fixture = new H2DatabaseFixture()) {
    fixture.refresh("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(80))");
    fixture.database().table("users").insert(Map.of("id", 1, "name", "Ada"));
    assertEquals(1, fixture.database().table("users").count());
    fixture.refresh("CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(80))");
    assertEquals(0, fixture.database().table("users").count());
}
```

Each fixture gets a uniquely named **in-memory H2** database; `refresh` drops and
recreates tables and `close` cleans up. This is **not** transaction rollback and not a
substitute for integration tests on PostgreSQL/MySQL. Never run it on an external DB.

### Typed factories

```java
AtomicInteger i = new AtomicInteger();
ModelFactory<User> factory = () -> new User((long)i.incrementAndGet(), "Example");
List<User> users = factory.makeMany(3);
```

Factories do not persist automatically: use a repository or query builder to persist.

## Mock outbound HTTP

```java
try (MockHttpServer mock = MockHttpServer.start()) {
    mock.when("GET", "/remote", 200, "application/json", "{\"ok\":true}");
    URI url = mock.uri("/remote");
    // Use FastFrame AsyncHttpClient.get(url) to test actual outbound requests.
    assertEquals(0, mock.calls("GET", "/remote"));
}
```

The mock binds `127.0.0.1` on an OS-assigned port. It is for integration testing;
no production HTTP calls are intercepted.

## Limits & remaining work

- This release does not include a JUnit extension, auto rollback for production DBs,
  request/response snapshot testing, websocket tests or full JSONPath filtering.
- Feature tests start a Netty server and must be closed (`try-with-resources`).
- A full Maven/Netty + Windows execution is required on your PC/CI to validate the package.
- Security and performance hardening are still required before v1.0.
