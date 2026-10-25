package dev.fastframe.observability;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import java.util.Objects;

/** Register after application routes and before accepting HTTP traffic. */
public final class OperationsRoutes {
    private OperationsRoutes() { }
    public static void register(Router router, Readiness readiness, HttpMetrics metrics, boolean exposeMetrics) {
        Objects.requireNonNull(router);
        Objects.requireNonNull(readiness);
        Objects.requireNonNull(metrics);
        router.get("/_health/live", request -> Response.json("{\"status\":\"alive\"}"));
        router.get("/_health/ready", request -> readiness.isReady()
                ? Response.json("{\"status\":\"ready\"}")
                : Response.json("{\"status\":\"not_ready\"}").status(503));
        if (exposeMetrics) {
            router.get("/_metrics", request -> Response.text(metrics.prometheus())
                    .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8"));
        }
    }
}
