package dev.fastframe.core.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A request object with a lifetime scoped to one dispatch. */
public final class Request {
    private final String method;
    private final String path;
    private final Map<String, List<String>> queryParameters;
    private final Map<String, String> headers;
    private final String body;
    private Map<String, String> routeParameters = Map.of();

    public Request(String method, String path, Map<String, List<String>> queryParameters,
                   Map<String, String> headers, String body) {
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.queryParameters = Objects.requireNonNull(queryParameters, "queryParameters");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.body = Objects.requireNonNull(body, "body");
    }

    public String method() { return method; }
    public String path() { return path; }
    public String body() { return body; }
    public String routeParam(String name) { return routeParameters.get(name); }
    public String query(String name) {
        List<String> values = queryParameters.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
    public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }

    // Only the router populates path parameters, before invoking the controller.
    void setRouteParameters(Map<String, String> params) { routeParameters = params; }
}
