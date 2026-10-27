package dev.fastframe.micronautbench;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Controller("/bench")
public final class AsyncJwtController {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String UPSTREAM = System.getenv().getOrDefault("BENCH_UPSTREAM_URL", "http://127.0.0.1:19090");

    @Get(uri="/async/{delay}", produces=MediaType.APPLICATION_JSON)
    public CompletionStage<HttpResponse<Map<String,Object>>> externalWait(@PathVariable("delay") String delay) {
        if (!delay.equals("10") && !delay.equals("50") && !delay.equals("200"))
            return CompletableFuture.completedFuture(HttpResponse.notFound());
        HttpRequest req = HttpRequest.newBuilder(URI.create(UPSTREAM + "/wait/" + delay))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return CLIENT.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
                .thenApply(reply -> {
                    if (reply.statusCode() != 200 || !reply.body().equals("{\"value\":\"ok\",\"delay_ms\":" + delay + "}"))
                        throw new IllegalStateException("Upstream returned unexpected response");
                    return HttpResponse.ok(Map.of("value", "ok", "delay_ms", Integer.parseInt(delay)));
                });
    }

    @Get(uri="/jwt", produces=MediaType.APPLICATION_JSON)
    public HttpResponse<Map<String,Object>> jwt(@Header(value="Authorization", defaultValue="") String authorization) {
        if (!BenchJwt.valid(authorization))
            return HttpResponse.unauthorized();
        return HttpResponse.ok(Map.of("subject", "bench-user", "authorized", true));
    }
}
