package dev.fastframe.httpclient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Immutable response snapshot; body() defensively copies bytes. */
public record HttpResult(int status, Map<String, List<String>> headers, byte[] body) {
    public HttpResult { headers = Map.copyOf(headers); body = body.clone(); }
    @Override public byte[] body() { return body.clone(); }
    public String text() { return new String(body, StandardCharsets.UTF_8); }
    public boolean successful() { return status >= 200 && status < 300; }
}
