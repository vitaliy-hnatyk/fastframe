package dev.fastframe.httpclient;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class AsyncHttpClientTest {
    @Test void asyncGetPostAndBodyLimit() throws Exception {
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/hello", exchange -> {
            byte[] data = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        local.createContext("/big", exchange -> {
            byte[] data = new byte[10000];
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        local.createContext("/post", exchange -> {
            byte[] data = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(201, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        local.start();
        try (var client = new AsyncHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(3), 1024)) {
            String base = "http://127.0.0.1:" + local.getAddress().getPort();
            assertEquals("ok", client.get(URI.create(base + "/hello")).get(5, TimeUnit.SECONDS).text());
            assertEquals(201, client.postJson(URI.create(base + "/post"), "{\"a\":1}").get(5, TimeUnit.SECONDS).status());
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.get(URI.create(base + "/big")).get(5, TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class, () -> client.get(URI.create("file:///etc/passwd")));
        } finally { local.stop(0); }
    }
}
