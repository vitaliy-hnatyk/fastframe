package dev.fastframe.testing;

import dev.fastframe.core.http.Json;
import dev.fastframe.core.http.Router;
import dev.fastframe.netty.NettyServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Feature test client that starts the actual Netty pipeline on an ephemeral loopback port. */
public final class TestClient implements AutoCloseable {
    private final NettyServer server;
    private final HttpClient client;
    private final URI root;
    private final Duration timeout;
    private boolean closed;

    private TestClient(NettyServer server, HttpClient client, int port, Duration timeout) {
        this.server = server;
        this.client = client;
        this.root = URI.create("http://127.0.0.1:" + port);
        this.timeout = timeout;
    }

    public static TestClient start(Router router) { return start(router, Duration.ofSeconds(5)); }

    public static TestClient start(Router router, Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid timeout");
        var server = new NettyServer(Objects.requireNonNull(router), "127.0.0.1", 0);
        try {
            server.start();
            return new TestClient(server, HttpClient.newBuilder()
                    .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(),
                    server.boundPort(), timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.close();
            throw new IllegalStateException("Interrupted starting test server", e);
        } catch (RuntimeException e) {
            server.close();
            throw e;
        }
    }

    public URI baseUri() { return root; }
    public TestResponse get(String path) { return session().get(path); }
    public TestResponse postJson(String path, Object value) { return session().postJson(path, value); }
    public TestResponse putJson(String path, Object value) { return session().putJson(path, value); }
    public TestResponse delete(String path) { return session().delete(path); }
    public Session session() { if (closed) throw new IllegalStateException("TestClient is closed"); return new Session(Map.of()); }
    public Session withBearerToken(String token) { return session().withBearerToken(token); }
    public Session withHeader(String name, String value) { return session().withHeader(name, value); }

    private TestResponse send(String method, String path, Map<String, String> headers, String body) {
        if (closed) throw new IllegalStateException("TestClient is closed");
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("#") ||
                path.indexOf('\\') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Expected a relative absolute-path, e.g. /api/users");
        }
        URI target = root.resolve(path);
        if (!Objects.equals(target.getAuthority(), root.getAuthority())) {
            throw new IllegalArgumentException("External test URL rejected");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(target).timeout(timeout);
        for (var header : headers.entrySet()) request.header(header.getKey(), header.getValue());
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            return new TestResponse(client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("HTTP feature test interrupted: " + method + " " + path, e);
        } catch (IOException e) {
            throw new AssertionError("HTTP feature test request failed: " + method + " " + path, e);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { client.close(); } finally { server.close(); }
    }

    /** Immutable per-request header set; useful for authenticated feature tests. */
    public final class Session {
        private final Map<String, String> headers;
        private Session(Map<String, String> headers) { this.headers = headers; }
        public Session withHeader(String name, String value) {
            Map<String, String> updated = new LinkedHashMap<>(headers);
            updated.put(Objects.requireNonNull(name), Objects.requireNonNull(value));
            return new Session(Map.copyOf(updated));
        }
        public Session withBearerToken(String token) {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("Token required");
            return withHeader("Authorization", "Bearer " + token);
        }
        public TestResponse get(String path) { return send("GET", path, headers, null); }
        public TestResponse delete(String path) { return send("DELETE", path, headers, null); }
        public TestResponse postJson(String path, Object object) { return json("POST", path, object); }
        public TestResponse putJson(String path, Object object) { return json("PUT", path, object); }
        public TestResponse json(String method, String path, Object object) {
            var all = new LinkedHashMap<>(headers);
            all.put("Content-Type", "application/json; charset=utf-8");
            return send(method, path, all, Json.stringify(object));
        }
    }
}
