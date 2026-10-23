package dev.fastframe.core.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable HTTP response. json(String) expects already serialized JSON. */
public final class Response {
    private final int status;
    private final String contentType;
    private final byte[] body;
    private final Map<String, String> headers;

    private Response(int status, String contentType, byte[] body, Map<String, String> headers) {
        if (status < 100 || status > 599) throw new IllegalArgumentException("Invalid HTTP status");
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        this.headers = headers;
    }

    public static Response text(String value) {
        return new Response(200, "text/plain; charset=utf-8",
                Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public static Response json(String alreadySerializedJson) {
        return new Response(200, "application/json; charset=utf-8",
                Objects.requireNonNull(alreadySerializedJson).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public Response status(int code) { return new Response(code, contentType, body, headers); }

    public Response header(String name, String value) {
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Invalid header name");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid header value");
        }
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new Response(status, contentType, body, Map.copyOf(copy));
    }

    public int status() { return status; }
    public String contentType() { return contentType; }
    public byte[] body() { return body; }
    public Map<String, String> headers() { return headers; }
}
