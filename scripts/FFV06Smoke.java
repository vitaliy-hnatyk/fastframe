import dev.fastframe.benchmark.BenchController;
import dev.fastframe.benchmark.EchoRequest;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.generated.FastFrameRoutes;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class FFV06Smoke {
    private static Response call(Router router, String method, String path, String body, Map<String, String> headers) {
        Request request = new Request(method, path, Map.of(), headers, body);
        return router.dispatchAsync(request).toCompletableFuture().join();
    }
    private static void match(Response response, String expected) {
        String actual = new String(response.body(), StandardCharsets.UTF_8);
        if (response.status() != 200 || !expected.equals(actual)) {
            throw new AssertionError(response.status() + " / " + actual + " expected " + expected);
        }
    }
    public static void main(String[] args) {
        Router router = new Router();
        FastFrameRoutes.register(router);
        match(call(router, "GET", "/bench/plain", "", Map.of()), "ok");
        match(call(router, "GET", "/bench/route/123", "", Map.of()), "123");
        String json = new String(call(router, "GET", "/bench/json", "", Map.of()).body(), StandardCharsets.UTF_8);
        if (!json.contains("\"message\":\"ok\"") || !json.contains("\"value\":42")) {
            throw new AssertionError(json);
        }
        match(call(router, "POST", "/bench/echo", "{\"message\":\"hello\"}",
                Map.of("content-type", "application/json")), "{\"message\":\"hello\"}");
        System.out.println("v0.6 BENCHMARK ROUTES PASS");
    }
}
