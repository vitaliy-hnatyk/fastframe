package dev.fastframe.demo;

import dev.fastframe.auth.BearerAuthMiddleware;
import dev.fastframe.auth.Identity;
import dev.fastframe.auth.JwtHmac256;
import dev.fastframe.core.http.Router;
import dev.fastframe.demo.routes.ApiRoutes;
import dev.fastframe.testing.TestClient;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FeatureAuthTest {
    @Test void routesRequireAuthenticationAndPermissionsOverNetty() {
        byte[] secret = new byte[32];
        java.util.Arrays.fill(secret, (byte) 7); // deterministic, TEST ONLY
        JwtHmac256 jwt = new JwtHmac256(secret, "test-issuer", "test-audience");
        Router router = new Router();
        router.use(new BearerAuthMiddleware(jwt));
        ApiRoutes.register(router, Runnable::run);
        try (TestClient app = TestClient.start(router)) {
            app.get("/api/secure/me").assertStatus(401);
            String token = jwt.issue(new Identity("user-42", Set.of(), Set.of()), Duration.ofMinutes(5));
            app.withBearerToken(token).get("/api/secure/me").assertStatus(200)
                    .assertJsonPath("$.subject", "user-42");
            app.withBearerToken(token).get("/api/secure/users").assertStatus(403);
            String allowed = jwt.issue(new Identity("user-42", Set.of(), Set.of("users.read")), Duration.ofMinutes(5));
            app.withBearerToken(allowed).get("/api/secure/users").assertStatus(200);
            app.withBearerToken("invalid-token").get("/api/secure/me").assertStatus(401);
        }
    }
}
