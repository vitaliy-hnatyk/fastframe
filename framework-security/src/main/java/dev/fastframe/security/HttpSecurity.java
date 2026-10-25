package dev.fastframe.security;

import java.util.Objects;

/** Shared, immutable HTTP security configuration; limiter state is shared across channels. */
public record HttpSecurity(CorsPolicy cors, IpRateLimiter limiter, boolean secureHeaders,
                           boolean hsts) {
    public HttpSecurity {
        Objects.requireNonNull(cors, "cors");
        if (hsts && !secureHeaders) throw new IllegalArgumentException("HSTS requires security headers");
    }
    /** Headers enabled; CORS/rate limiting disabled until explicitly configured. */
    public static HttpSecurity defaults() { return new HttpSecurity(CorsPolicy.disabled(), null, true, false); }
}
