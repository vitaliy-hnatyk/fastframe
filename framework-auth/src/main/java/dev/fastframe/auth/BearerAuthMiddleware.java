package dev.fastframe.auth;
import dev.fastframe.core.http.Middleware;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
/** Optional authentication for public routes; annotation guards enforce private routes. */
public final class BearerAuthMiddleware implements Middleware {
    private final JwtHmac256 jwt;
    public BearerAuthMiddleware(JwtHmac256 jwt) { this.jwt = Objects.requireNonNull(jwt); }
    @Override public CompletionStage<Response> handle(Request request, Next next) throws Exception {
        String header = request.header("authorization");
        if (header != null) {
            if (!header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7
                    || header.length() > 4103 || header.substring(7).contains(" ")) {
                return CompletableFuture.completedFuture(Response.json("{\"error\":\"unauthorized\"}").status(401).header("WWW-Authenticate", "Bearer").header("Cache-Control", "no-store"));
            }
            try {
                request.securityIdentity(jwt.verify(header.substring(7)));
            } catch (IllegalArgumentException e) {
                return CompletableFuture.completedFuture(Response.json("{\"error\":\"unauthorized\"}")
                        .status(401).header("WWW-Authenticate", "Bearer").header("Cache-Control", "no-store"));
            }
        }
        return next.proceed(request);
    }
}
