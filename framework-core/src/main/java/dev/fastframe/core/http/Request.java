package dev.fastframe.core.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A request object with a lifetime scoped to one dispatch. */
public final class Request {
    private final String requestId;
    private final String method;
    private final String path;
    private final Map<String, List<String>> queryParameters;
    private final Map<String, String> headers;
    private final String body;
    private final byte[] binaryBody;
    private Map<String, String> routeParameters = Map.of();
    private final CancellationToken cancellation = new CancellationToken();
    // Bound to this request, not a ThreadLocal (async requests may resume on another thread).
    private volatile Object securityIdentity;

    public Request(String method, String path, Map<String, List<String>> queryParameters,
                   Map<String, String> headers, String body) {
        this(method, path, queryParameters, headers, body, null);
    }

    /** Optional raw bytes for application/octet-stream uploads; read once per request. */
    public Request(String method, String path, Map<String, List<String>> queryParameters,
                   Map<String, String> headers, String body, byte[] binaryBody) {
        this(method, path, queryParameters, headers, body, binaryBody, "");
    }

    /** Server-generated correlation ID; never trust a client supplied X-Request-ID. */
    public Request(String method, String path, Map<String, List<String>> queryParameters,
                   Map<String, String> headers, String body, byte[] binaryBody, String requestId) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.queryParameters = Objects.requireNonNull(queryParameters, "queryParameters");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.body = Objects.requireNonNull(body, "body");
        this.binaryBody = binaryBody == null ? null : binaryBody.clone();
    }

    public CancellationToken cancellation() { return cancellation; }
    /** Security extensions may attach an authenticated identity scoped to this request only. */
    public void securityIdentity(Object identity) { this.securityIdentity = Objects.requireNonNull(identity); }
    public Object securityIdentity() { return securityIdentity; }
    public String requestId() { return requestId; }
    public String method() { return method; }
    public String path() { return path; }
    public String body() { return body; }
    /** Returns independent bytes; UTF-8 bytes are computed only when called for text bodies. */
    public byte[] bodyBytes() { return binaryBody == null ? body.getBytes(java.nio.charset.StandardCharsets.UTF_8) : binaryBody.clone(); }
    public String routeParam(String name) { return routeParameters.get(name); }
    public String query(String name) {
        List<String> values = queryParameters.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
    public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

    // Only the router populates path parameters, before invoking the controller.
    void setRouteParameters(Map<String, String> params) { routeParameters = params; }
}
