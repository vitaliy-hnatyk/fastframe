package dev.fastframe.testing;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Local outbound-HTTP mock for integration tests; never binds external interfaces. */
public final class MockHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private MockHttpServer(HttpServer server) { this.server = server; }
    public static MockHttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var mock = new MockHttpServer(server);
            server.createContext("/", exchange -> {
                String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
                mock.calls.computeIfAbsent(key, unused -> new AtomicInteger()).incrementAndGet();
                var reply = mock.replies.getOrDefault(key, new Reply(404, "text/plain", "Not Found"));
                var bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", reply.contentType());
                try {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(reply.status(), bytes.length);
                    try (var body = exchange.getResponseBody()) { body.write(bytes); }
                } finally { exchange.close(); }
            });
            server.start();
            return mock;
        } catch (IOException ex) { throw new IllegalStateException("Could not start mock HTTP server", ex); }
    }
    public MockHttpServer when(String method, String path, int status, String contentType, String body) {
        if (method == null || !method.matches("[A-Z]+") || path == null || !path.startsWith("/") || path.contains("?"))
            throw new IllegalArgumentException("Invalid mock route");
        if (status < 200 || status > 599) throw new IllegalArgumentException("Invalid mock status");
        replies.put(method + " " + path, new Reply(status, Objects.requireNonNull(contentType), Objects.requireNonNull(body)));
        return this;
    }
    public int calls(String method, String path) {
        var count = calls.get(method + " " + path);
        return count == null ? 0 : count.get();
    }
    public URI uri(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("Invalid relative path");
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }
    @Override public void close() { server.stop(0); }
    private record Reply(int status, String contentType, String body) { }
}
