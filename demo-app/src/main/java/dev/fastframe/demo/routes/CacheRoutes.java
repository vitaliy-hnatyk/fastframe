package dev.fastframe.demo.routes;

import dev.fastframe.cache.Cache;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;

import java.time.Duration;
import java.util.Map;

/** Demonstration of in-memory caching; no Redis dependency on the demo app. */
public final class CacheRoutes {
    private CacheRoutes() { }

    public static void register(Router router, Cache cache) {
        router.get("/api/cache/demo", request -> Response.jsonValue(Map.of(
            "value", cache.remember("demo:greeting", Duration.ofSeconds(30), () -> "Hello from FastFrame Cache")
        )));
    }
}
