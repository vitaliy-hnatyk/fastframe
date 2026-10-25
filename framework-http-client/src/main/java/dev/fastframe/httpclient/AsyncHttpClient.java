package dev.fastframe.httpclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** JDK 21 async HTTP client. Destinations MUST be trusted/allowlisted by application code. */
public final class AsyncHttpClient implements AutoCloseable {
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int maxBodyBytes;

    public AsyncHttpClient(Duration connectTimeout, Duration requestTimeout, int maxBodyBytes) {
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero() ||
                requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero() ||
                maxBodyBytes < 1 || maxBodyBytes > 100 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid HTTP client limits");
        }
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.requestTimeout = requestTimeout;
        this.maxBodyBytes = maxBodyBytes;
    }

    private URI validated(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) ||
                uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Expected an HTTP(S) URI without userinfo");
        }
        return uri;
    }

    public CompletableFuture<HttpResult> get(URI uri) {
        return send(HttpRequest.newBuilder(validated(uri)).timeout(requestTimeout).GET().build());
    }

    public CompletableFuture<HttpResult> postJson(URI uri, String json) {
        Objects.requireNonNull(json, "json");
        return send(HttpRequest.newBuilder(validated(uri)).timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build());
    }

    public CompletableFuture<HttpResult> send(HttpRequest request) {
        validated(request.uri());
        HttpResponse.BodyHandler<byte[]> bounded = info -> {
            long advertised = info.headers().firstValueAsLong("content-length").orElse(-1);
            return new BoundedBodySubscriber(advertised > maxBodyBytes ? -1 : maxBodyBytes);
        };
        return client.sendAsync(request, bounded)
                .thenApply(response -> new HttpResult(response.statusCode(), response.headers().map(), response.body()));
    }
    @Override public void close() { client.close(); }
}
