package dev.fastframe.security;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit exact-origin CORS allowlist; NOT an authentication or CSRF mechanism. */
public final class CorsPolicy {
    private final Set<String> origins;
    private final Set<String> methods;
    private final Set<String> headers;
    private final boolean credentials;
    private final int maxAgeSeconds;

    public CorsPolicy(Set<String> origins, Set<String> methods, Set<String> headers,
                      boolean credentials, int maxAgeSeconds) {
        Objects.requireNonNull(origins); Objects.requireNonNull(methods); Objects.requireNonNull(headers);
        if (maxAgeSeconds < 0 || maxAgeSeconds > 86400) throw new IllegalArgumentException("Invalid CORS max age");
        for (String origin : origins) validateOrigin(origin);
        this.origins = Set.copyOf(origins);
        if (methods.isEmpty()) throw new IllegalArgumentException("CORS methods required");
        this.methods = methods.stream().map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        this.headers = headers.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        for (String m : this.methods) if (!m.matches("[A-Z-]+")) throw new IllegalArgumentException("Invalid CORS method");
        for (String h : this.headers) if (!h.matches("[a-z0-9-]+")) throw new IllegalArgumentException("Invalid CORS header");
        this.credentials = credentials;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public static CorsPolicy disabled() {
        return new CorsPolicy(Set.of(), Set.of("GET", "POST", "OPTIONS"), Set.of("content-type"), false, 0);
    }

    private static void validateOrigin(String value) {
        if (value == null || value.isBlank() || value.equals("*") || value.indexOf(',') >= 0
                || !value.equals(value.trim())) throw new IllegalArgumentException("CORS requires an exact origin");
        try {
            URI uri = URI.create(value);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getRawAuthority() == null || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !value.equals(uri.getScheme() + "://" + uri.getRawAuthority())) {
                throw new IllegalArgumentException("Invalid CORS origin: " + value);
            }
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid CORS origin: " + value, e); }
    }

    public boolean enabled() { return !origins.isEmpty(); }
    public boolean allowsOrigin(String origin) { return origin != null && origins.contains(origin); }
    public boolean allowCredentials() { return credentials; }
    public int maxAgeSeconds() { return maxAgeSeconds; }
    public String methodsHeader() { return String.join(", ", methods.stream().sorted().toList()); }
    public String headersHeader() { return String.join(", ", headers.stream().sorted().toList()); }

    /** All requested method/header names must be explicitly allowlisted. */
    public boolean allowsPreflight(String method, String requestedHeaders) {
        if (method == null || !methods.contains(method.toUpperCase(Locale.ROOT))) return false;
        if (requestedHeaders == null || requestedHeaders.isBlank()) return true;
        return Arrays.stream(requestedHeaders.split(",", -1))
                .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT))
                .allMatch(s -> !s.isEmpty() && headers.contains(s));
    }
}
