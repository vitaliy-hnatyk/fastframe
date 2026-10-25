package dev.fastframe.netty;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.observability.HttpMetrics;
import dev.fastframe.observability.Readiness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class NettyGracefulShutdownTest {
    @Test @Timeout(12)
    void drainsAnInFlightResponseBeforeShuttingDown() throws Exception {
        var slow = new CompletableFuture<Response>();
        var started = new CountDownLatch(1);
        var ready = new Readiness();
        var metrics = new HttpMetrics();
        var routes = new Router().getAsync("/slow", req -> {
            started.countDown();
            return slow;
        });
        try (NettyServer server = new NettyServer(routes, "127.0.0.1", 0,
                new NettyOptions(8, 8, 4096, Duration.ofSeconds(4)), metrics, ready, false);
             HttpClient client = HttpClient.newHttpClient()) {
            server.start();
            assertTrue(ready.isReady());
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.boundPort() + "/slow"))
                    .GET().build();
            var http = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            assertTrue(started.await(3, TimeUnit.SECONDS));
            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(() ->
                    server.shutdownGracefully(Duration.ofSeconds(3)));
            // The listening socket closes before active response completes.
            Thread.sleep(100);
            assertFalse(ready.isReady());
            slow.complete(Response.text("finished"));
            var response = http.get(3, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertEquals("finished", response.body());
            assertFalse(response.headers().firstValue("X-Request-ID").orElse("").isEmpty());
            shutdown.get(3, TimeUnit.SECONDS);
            assertEquals(0, metrics.activeRequests());
        }
    }
}
