# FastFrame v0.12 — Cache, TTL & Redis

FastFrame Cache offers a small Laravel-inspired API without serialization magic.
**Cache values are Strings** (including JSON strings if you encode them yourself); null is rejected.
There is no automatic arbitrary Java object serialization or deserialization.

## Modules

- `framework-cache`: `Cache`, `CacheStore`, `InMemoryCacheStore`, JDK only.
- `framework-cache-redis`: optional Redis adapter using Lettuce `7.9.0.RELEASE`.
  The demo **does not depend on Redis**.

## API

```java
Cache cache = new Cache(new InMemoryCacheStore(10_000));
cache.put("greeting", "hello", Duration.ofMinutes(5));
String name = cache.getOrDefault("greeting", "unknown");
boolean hit = cache.has("greeting");
boolean inserted = cache.add("one-time", "value", Duration.ofSeconds(10));
String result = cache.remember("report", Duration.ofMinutes(1), () -> expensiveReport());
String durable = cache.rememberForever("settings", () -> "data");
cache.forever("forever", "value");
boolean removed = cache.forget("greeting");
```

* `get(key)` returns `Optional<String>`, distinguishing a missing value from `""`.
* TTL must be positive; invalid or excessive durations are rejected.
* In-memory TTL uses `System.nanoTime`, is bounded by a configurable **LRU capacity**,
  and is process-local and volatile. The in-memory backend is synchronized and is **not optimized
  for very high concurrent access**; benchmark before using it as a hot-path shared cache.
* `remember(...)` uses striped local locks to avoid repeated supplier execution in one `Cache`
  instance. It does not provide a distributed single-flight guarantee across applications.
* `add` is atomic for in-memory and Redis (Redis `SET NX PX`).
* No wildcard flush operation: deleting all of a shared Redis database would be unsafe.

## Redis (opt-in)

Add Maven dependency (only if using Redis):

```xml
<dependency>
  <groupId>dev.fastframe</groupId>
  <artifactId>framework-cache-redis</artifactId>
  <version>0.12.0-SNAPSHOT</version>
</dependency>
```

```java
String url = System.getenv("FF_REDIS_URI"); // e.g. rediss://user:password@host:6379/0
if (url == null || url.isBlank()) throw new IllegalStateException("FF_REDIS_URI missing");
try (RedisCacheStore store = new RedisCacheStore(url, "fastframe:my-app:")) {
    Cache cache = new Cache(store);
    cache.put("hello", "world", Duration.ofSeconds(30));
    System.out.println(cache.get("hello"));
}
```

Import `dev.fastframe.cache.Cache` and `dev.fastframe.cache.redis.RedisCacheStore`.
Keep Redis URI credentials out of source code; use `rediss://` for TLS when appropriate.
The adapter owns the Lettuce client/connection and should be closed on application shutdown.

**Concurrency & Netty safety:** `RedisCacheStore` uses the synchronous Lettuce API;
never invoke Redis I/O in a Netty event-loop controller. Call `Cache.rememberAsync(..., workerExecutor)`
or run cache operations within a bounded executor. A small worker pool is already used for
blocking JDBC in the demo. Timeouts/failure modes must be handled by the caller.

Redis tests are skipped unless `FF_TEST_REDIS_URI` points to a disposable Redis service:

```powershell
$env:FF_TEST_REDIS_URI = 'redis://127.0.0.1:6379'
mvn -B -ntp -pl framework-cache-redis -am test
```

## Windows — build and run with Java 21

```powershell
cd C:\xampp\www\fastframe
Set-ExecutionPolicy -Scope Process Bypass -Force
.\scripts\cache-smoke.ps1
.\scripts\run-demo.ps1
# Another PowerShell:
curl.exe http://localhost:8080/api/cache/demo
```

The PowerShell scripts use configured Temurin JDK 21 in the current process; system Java 8 is unchanged.

## Limits and next steps

This is **experimental**, not production-ready. Missing: key tagging/invalidation, serializers,
Redis Cluster/Sentinel configuration through FastFrame, cache metrics, distributed locks,
rate-limiting primitives, advanced stampede protection, and benchmark data.
Unlike the demo's local in-memory cache, networked Redis must be profiled with real contention.
