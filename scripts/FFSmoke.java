import dev.fastframe.core.http.*;
import dev.fastframe.demo.routes.ApiRoutes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** Executes the generated annotation routes without Maven or Netty dependencies. */
public class FFSmoke {
    static Request request(String method, String path, Map<String,String> headers, Map<String, List<String>> query) {
        return new Request(method, path, query, headers, "");
    }
    static Request request(String method, String path) { return request(method, path, Map.of(), Map.of()); }
    static Request jsonRequest(String contentType, String body) {
        return new Request("POST", "/api/users", Map.of(), Map.of("content-type", contentType), body);
    }
    static Throwable failure(Router router, Request request) {
        try { call(router, request); throw new AssertionError("Expected a rejected request"); }
        catch (CompletionException ex) { return ex.getCause(); }
    }
    static void ensure(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static Response call(Router router, Request req) { return router.dispatchAsync(req).toCompletableFuture().join(); }
    static String body(Response response) { return new String(response.body(), StandardCharsets.UTF_8); }

    public static void main(String[] args) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(5));
        try {
            Router router = new Router();
            ApiRoutes.register(router, pool);
            Response user = call(router, request("GET", "/api/users/42"));
            ensure(user.status() == 200 && body(user).contains("user-42"), "generated DI + typed route");
            ensure("FastFrame".equals(user.headers().get("X-Framework")), "global middleware header");
            ensure(call(router, request("GET", "/api/users/not-a-number")).status() == 400, "invalid numeric path -> 400");
            ensure(call(router, request("GET", "/api/users/page", Map.of(), Map.of("number", List.of("3")))).status() == 200, "query binding");
            ensure(call(router, request("GET", "/api/users/page")).status() == 400, "missing int query -> 400");
            ensure(call(router, request("GET", "/api/users/async/7")).status() == 200, "generated async");
            Response created = call(router, jsonRequest("application/json; charset=utf-8",
                    "{\"name\":\"Ana\",\"email\":\"ana@example.org\",\"age\":21}"));
            ensure(created.status() == 201 && body(created).contains("ana@example.org"), "generated POST DTO binding");
            Throwable invalid = failure(router, jsonRequest("application/json",
                    "{\"name\":\"A\",\"email\":false,\"age\":2}"));
            ensure(invalid instanceof ValidationException && ((ValidationException)invalid).errors().size() == 3,
                    "field validation aggregates errors");
            ensure(failure(router, jsonRequest("application/json", "{\"name\":\"A\",\"name\":\"B\"}"))
                    instanceof HttpException, "duplicate JSON field rejected");
            ensure(failure(router, jsonRequest("text/plain", "{}")) instanceof HttpException,
                    "JSON content type checked");
            ensure(call(router, request("GET", "/api/users/42", Map.of("x-demo-block", "true"), Map.of())).status() == 403, "middleware block");
            ensure(call(router, request("GET", "/api/async-demo")).status() == 200, "manual async route");
            ensure(call(router, request("HEAD", "/api/health")).status() == 200, "HEAD");
            ensure(call(router, request("OPTIONS", "/api/health")).status() == 204, "OPTIONS");
            ensure(call(router, request("GET", "/none")).status() == 404, "404");
            CompletableFuture<Response> pending = new CompletableFuture<>();
            Router asyncRouter = new Router().getAsync("/wait", req -> pending);
            var result = asyncRouter.dispatchAsync(request("GET", "/wait"));
            ensure(!result.toCompletableFuture().isDone(), "nonblocking controller");
            pending.complete(Response.text("done"));
            ensure(result.toCompletableFuture().join().status() == 200, "completed async");
            System.out.println("SMOKE PASS: generated DI/routes, JSON DTO binding, validation, async, HTTP status");
        } finally { pool.shutdownNow(); }
    }
}
