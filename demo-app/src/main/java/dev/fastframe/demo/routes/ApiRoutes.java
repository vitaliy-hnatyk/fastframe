package dev.fastframe.demo.routes;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.demo.controllers.UserController;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class ApiRoutes {
    private ApiRoutes() { }

    public static void register(Router router, Executor blockingExecutor) {
        UserController users = new UserController();
        router.use((request, next) -> next.proceed(request).thenApply(response -> response.header("X-Framework", "FastFrame")));
        router.get("/", request -> Response.text("FastFrame 0.2 on Netty"));
        router.group("/api", api -> {
            api.middleware((request, next) -> {
                if (request.header("x-demo-block") != null) {
                    return CompletableFuture.completedFuture(Response.text("Forbidden by demo middleware").status(403));
                }
                return next.proceed(request);
            });
            api.get("/health", request -> Response.json("{\"status\":\"ok\"}"));
            api.get("/users/{id}", users::show);
            api.post("/users", users::store);
            // Demo only: shows how work is offloaded, NOT an actual database implementation.
            api.getAsync("/async-demo", request -> CompletableFuture.supplyAsync(
                    () -> Response.json("{\"async\":true}"), blockingExecutor));
        });
    }
}
