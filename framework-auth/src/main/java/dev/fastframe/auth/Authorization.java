package dev.fastframe.auth;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import java.util.Objects;
import java.util.Optional;
/** Explicit, stateless authorization; no ThreadLocal security context. */
public final class Authorization {
    private Authorization() { }
    public static Optional<Identity> identity(Request request) {
        Object value = Objects.requireNonNull(request).securityIdentity();
        return value instanceof Identity identity ? Optional.of(identity) : Optional.empty();
    }
    /** Returns null when allowed; otherwise 401/403 with no internal details. */
    public static Response require(Request request, String permission) {
        var maybe = identity(request);
        if (maybe.isEmpty()) {
            return Response.json("{\"error\":\"unauthorized\"}").status(401)
                    .header("WWW-Authenticate", "Bearer").header("Cache-Control", "no-store");
        }
        if (permission != null && !maybe.get().grants(permission)) {
            return Response.json("{\"error\":\"forbidden\"}").status(403).header("Cache-Control", "no-store");
        }
        return null;
    }
    /** Resource-level policies can be used inside controllers or services. */
    public static <T> boolean can(Request request, T resource, Policy<T> policy) {
        return identity(request).map(id -> policy.allows(id, resource)).orElse(false);
    }
}
