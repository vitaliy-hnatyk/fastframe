import dev.fastframe.core.http.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class FFV07Smoke {
    static Request req(String method, String path) {
        return new Request(method, path, Map.of(), Map.of(), "");
    }
    static String body(Response response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Router router = new Router();
        router.get("/users/{id}", r -> Response.text("parameter:" + r.routeParam("id")));
        router.get("/users/new", r -> Response.text("static"));
        router.group("/v1", g -> g.get("/ping", r -> Response.text("pong")));
        Object staticResult = router.dispatchDirect(req("GET", "/users/new"));
        require(staticResult instanceof Response, "sync handlers must return Response directly");
        require("static".equals(body((Response) staticResult)), "static route has precedence");
        require("parameter:123".equals(body((Response) router.dispatchDirect(req("GET", "/users/123")))), "path param");
        require("pong".equals(body((Response) router.dispatchDirect(req("GET", "/v1/ping/")))), "trailing slash");
        require("static".equals(body((Response) router.dispatchDirect(req("HEAD", "/users/new")))), "HEAD fallback");
        require(router.dispatchDirect(req("PUT", "/users/new")) instanceof Response, "method not allowed direct");
        require(((Response) router.dispatchDirect(req("PUT", "/users/new"))).status() == 405, "405");
        require(((Response) router.dispatchDirect(req("OPTIONS", "/users/new"))).status() == 204, "OPTIONS");
        require(((Response) router.dispatchDirect(req("GET", "/missing"))).status() == 404, "404");
        require("static".equals(body(router.dispatchAsync(req("GET", "/users/new")).toCompletableFuture().join())), "async API compatibility");

        CompletableFuture<Response> waiting = new CompletableFuture<>();
        Router async = new Router().getAsync("/slow", r -> waiting);
        Object pending = async.dispatchDirect(req("GET", "/slow"));
        require(pending == waiting, "async stage preserved");
        waiting.complete(Response.text("finished"));
        require("finished".equals(body(((CompletionStage<Response>)pending).toCompletableFuture().join())), "async response");

        Router middleware = new Router().use((r, next) -> next.proceed(r).thenApply(resp -> resp.header("X-MW", "yes")));
        middleware.get("/ok", r -> Response.text("middleware"));
        Object composed = middleware.dispatchDirect(req("GET", "/ok"));
        require(composed instanceof CompletionStage<?>, "middleware preserves async semantics");
        require("yes".equals(((CompletionStage<Response>)composed).toCompletableFuture().join().headers().get("X-MW")), "middleware executed");
        System.out.println("V0.7 OPTIMIZATION SMOKE PASS: direct sync dispatch, async, middleware, precedence, HEAD/OPTIONS/404/405");
    }
}
