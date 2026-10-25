package dev.fastframe.demo.routes;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.queue.Jobs;
import java.time.Duration;
import java.util.Map;

/** Enqueue only; actual job handler runs on a separate worker thread. */
public final class QueueRoutes {
    private QueueRoutes() {}
    public static void register(Router router, Jobs jobs) {
        router.post("/api/jobs/demo", request -> {
            var id=jobs.dispatch("demo.greeting", "Hello from FastFrame background job")
                    .delay(Duration.ZERO).maxAttempts(3).enqueue();
            return Response.jsonValue(Map.of("queued",true,"id",id.toString())).status(202);
        });
    }
}
