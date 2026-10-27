package dev.fastframe.benchmark;

import dev.fastframe.core.annotation.Controller;
import dev.fastframe.core.annotation.Get;
import dev.fastframe.core.annotation.PathParam;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;

@Controller("/bench")
public final class AsyncJwtController {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String UPSTREAM = System.getenv().getOrDefault("BENCH_UPSTREAM_URL", "http://127.0.0.1:19090");

    @Get("/async/{delay}")
    public CompletionStage<Response> externalWait(@PathParam("delay") String delay) {
        if (!delay.equals("10") && !delay.equals("50") && !delay.equals("200"))
            return java.util.concurrent.CompletableFuture.completedFuture(Response.text("Not Found").status(404));
        HttpRequest req = HttpRequest.newBuilder(URI.create(UPSTREAM + "/wait/" + delay))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(reply -> {
                    if (reply.statusCode() != 200 || !reply.body().equals("{\"value\":\"ok\",\"delay_ms\":" + delay + "}"))
                        throw new IllegalStateException("Upstream returned unexpected response");
                    return Response.jsonValue(Map.of("value", "ok", "delay_ms", Integer.parseInt(delay)));
                });
    }

    @Get("/jwt")
    public Response jwt(Request request) {
        if (!BenchJwt.valid(request.header("authorization")))
            return Response.jsonValue(Map.of("error", "unauthorized")).status(401);
        return Response.jsonValue(Map.of("subject", "bench-user", "authorized", true));
    }
}
