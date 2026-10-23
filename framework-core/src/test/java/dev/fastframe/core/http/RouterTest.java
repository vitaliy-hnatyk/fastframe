package dev.fastframe.core.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

final class RouterTest {
    private static Request request(String method, String path) {
        return new Request(method, path, Map.of(), Map.of(), "");
    }

    @Test
    void matchesStaticAndParameterizedRoutes() throws Exception {
        Router router = new Router();
        router.get("/users/new/details", r -> Response.text("details"));
        router.get("/users/new", r -> Response.text("new"));
        router.get("/users/{id}", r -> Response.text(r.routeParam("id")));
        assertEquals("new", body(router.dispatch(request("GET", "/users/new"))));
        assertEquals("details", body(router.dispatch(request("GET", "/users/new/details"))));
        assertEquals("42", body(router.dispatch(request("GET", "/users/42"))));
    }

    @Test
    void routeGroupsStatusCodesAndHeadOptions() throws Exception {
        Router router = new Router().group("/api", api -> api.post("/jobs", r -> Response.text("ok")));
        assertEquals(200, router.dispatch(request("POST", "/api/jobs")).status());
        Response wrongMethod = router.dispatch(request("GET", "/api/jobs"));
        assertEquals(405, wrongMethod.status());
        assertEquals("POST, OPTIONS", wrongMethod.headers().get("Allow"));
        Response options = router.dispatch(request("OPTIONS", "/api/jobs"));
        assertEquals(204, options.status());
        assertEquals("POST, OPTIONS", options.headers().get("Allow"));
        assertEquals(404, router.dispatch(request("GET", "/unknown")).status());

        router.get("/resource", r -> Response.text("abc"));
        assertEquals("abc", body(router.dispatch(request("HEAD", "/resource"))));
        assertEquals("GET, HEAD, OPTIONS", router.dispatch(request("OPTIONS", "/resource")).headers().get("Allow"));
    }

    @Test
    void asyncRouteReturnsIncompleteStageWithoutBlocking() {
        CompletableFuture<Response> pending = new CompletableFuture<>();
        Router router = new Router().getAsync("/wait", request -> pending);
        var stage = router.dispatchAsync(request("GET", "/wait"));
        assertFalse(stage.toCompletableFuture().isDone());
        pending.complete(Response.text("finished"));
        assertEquals("finished", body(stage.toCompletableFuture().join()));
    }

    @Test
    void middlewareCanShortCircuitAndOrdersCorrectly() {
        List<String> visited = new ArrayList<>();
        Router router = new Router();
        router.use((request, next) -> {
            visited.add("global-before");
            return next.proceed(request).thenApply(response -> {
                visited.add("global-after");
                return response.header("X-Global", "yes");
            });
        });
        router.group("/api", group -> {
            group.middleware((request, next) -> {
                visited.add("group-before");
                return next.proceed(request).thenApply(response -> {
                    visited.add("group-after");
                    return response;
                });
            });
            group.get("/ok", request -> {
                visited.add("controller");
                return Response.text("ok");
            });
            group.getAsync("/restricted", request -> {
                fail("must not execute");
                return CompletableFuture.completedFuture(Response.text("unexpected"));
            });
        });
        var good = router.dispatchAsync(request("GET", "/api/ok")).toCompletableFuture().join();
        assertEquals("yes", good.headers().get("X-Global"));
        assertEquals(List.of("global-before", "group-before", "controller", "group-after", "global-after"), visited);
    }

    @Test
    void shortCircuitBlocksControllerAndStillRunsOuterMiddleware() {
        Router router = new Router().use((req, next) -> next.proceed(req)
                .thenApply(response -> response.header("X-Outer", "done")));
        router.group("/private", group -> {
            group.middleware((req, next) -> CompletableFuture.completedFuture(Response.text("blocked").status(403)));
            group.get("/secret", req -> {
                fail("should not be called");
                return Response.text("secret");
            });
        });
        Response blocked = router.dispatchAsync(request("GET", "/private/secret")).toCompletableFuture().join();
        assertEquals(403, blocked.status());
        assertEquals("done", blocked.headers().get("X-Outer"));
    }

    @Test
    void thrownErrorsBecomeFailedAsyncResults() {
        Router router = new Router().getAsync("/explode", request -> {
            throw new IllegalStateException("failure");
        });
        var future = router.dispatchAsync(request("GET", "/explode")).toCompletableFuture();
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void protectsMiddlewareRegistrationOrderAndDuplicateRoutes() {
        Router router = new Router().get("/hello", request -> Response.text("ok"));
        assertThrows(IllegalStateException.class, () -> router.use((req, next) -> next.proceed(req)));
        assertThrows(IllegalArgumentException.class,
                () -> router.get("/hello", request -> Response.text("again")));
    }

    @Test
    void jsonQuotesUnsafeCharacters() {
        assertEquals("\"x\\\"\\n\"", Json.quote("x\"\n"));
    }

    private static String body(Response response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
