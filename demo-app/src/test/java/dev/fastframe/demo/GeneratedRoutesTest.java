package dev.fastframe.demo;

import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.core.http.ValidationException;
import dev.fastframe.core.http.HttpException;
import dev.fastframe.demo.routes.ApiRoutes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

final class GeneratedRoutesTest {
    private final Router router = makeRouter();

    private Router makeRouter() {
        Router r = new Router();
        // Direct executor is safe here because demo async work is non-blocking.
        Executor executor = Runnable::run;
        ApiRoutes.register(r, executor);
        return r;
    }

    private Response call(String method, String path, Map<String, List<String>> query) {
        return router.dispatchAsync(new Request(method, path, query, Map.of(), ""))
                .toCompletableFuture().join();
    }

    @Test void generatedConstructorWiringAndRoute() {
        Response result = call("GET", "/api/users/8", Map.of());
        assertEquals(200, result.status());
        assertEquals("{\"id\":8,\"name\":\"user-8\"}", new String(result.body(), StandardCharsets.UTF_8));
        assertEquals("FastFrame", result.headers().get("X-Framework"));
    }

    @Test void typedPathAndQueryBinding() {
        assertEquals(400, call("GET", "/api/users/nan", Map.of()).status());
        assertEquals(400, call("GET", "/api/users/page", Map.of()).status());
        assertEquals(200, call("GET", "/api/users/page", Map.of("number", List.of("7"))).status());
        assertEquals(200, call("GET", "/api/users/async/3", Map.of()).status());
    }

    @Test void rejectsInvalidDtoWithFieldErrors() {
        Request posted = new Request("POST", "/api/users", Map.of(), Map.of("content-type", "application/json"),
                "{\"name\":\"a\",\"email\":42,\"age\":4}");
        var failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> router.dispatchAsync(posted).toCompletableFuture().join());
        ValidationException error = assertInstanceOf(ValidationException.class, failure.getCause());
        assertEquals(3, error.errors().size());
    }

    @Test void generatedPostAndHttpFallbacks() {
        Request posted = new Request("POST", "/api/users", Map.of(), Map.of("content-type", "application/json"),
                "{\"name\":\"Lina\",\"email\":\"lina@example.com\",\"age\":28}");
        assertEquals(201, router.dispatchAsync(posted).toCompletableFuture().join().status());
        assertEquals(405, call("DELETE", "/api/users/8", Map.of()).status());
        assertEquals(204, call("OPTIONS", "/api/users/8", Map.of()).status());
    }
}
