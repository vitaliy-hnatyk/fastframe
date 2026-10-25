package dev.fastframe.auth;
import dev.fastframe.core.http.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AuthTest {
    private final byte[] key = new byte[32];
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T12:00:00Z"), ZoneOffset.UTC);
    private JwtHmac256 codec() { return new JwtHmac256(key, "issuer", "api", clock, Duration.ofHours(1)); }
    private final Identity id = new Identity("u-1", Set.of("admin"), Set.of("users.read"));
    @Test void validTokenAndGrants() {
        Identity got = codec().verify(codec().issue(id, Duration.ofMinutes(5)));
        assertEquals(id, got); assertTrue(got.grants("users.read")); assertTrue(got.grants("role:admin"));
        assertFalse(got.grants("users.write"));
    }
    @Test void rejectsTamperingAlgorithmNoneAndIncorrectAudience() {
        String jwt = codec().issue(id, Duration.ofMinutes(5));
        String[] split = jwt.split("\\.");
        assertThrows(IllegalArgumentException.class, () -> codec().verify(split[0]+"."+split[1]+".invalid"));
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        assertThrows(IllegalArgumentException.class, () -> codec().verify(none+"."+split[1]+"."+split[2]));
        assertThrows(IllegalArgumentException.class, () ->
            new JwtHmac256(key, "issuer", "other", clock, Duration.ofHours(1)).verify(jwt));
    }
    @Test void rejectsExpiredAndWeakKey() {
        String jwt = codec().issue(id, Duration.ofMinutes(1));
        assertThrows(IllegalArgumentException.class, () ->
            new JwtHmac256(key,"issuer","api",Clock.offset(clock,Duration.ofMinutes(2)),Duration.ofHours(1)).verify(jwt));
        assertThrows(IllegalArgumentException.class, () -> new JwtHmac256(new byte[16],"issuer","api"));
    }
    @Test void authGuardReturns401And403() {
        Request request = new Request("GET","/secure", Map.of(), Map.of(), "");
        assertEquals(401, Authorization.require(request, null).status());
        request.securityIdentity(id);
        assertEquals(403, Authorization.require(request, "users.write").status());
        assertNull(Authorization.require(request, "users.read"));
        assertTrue(Authorization.can(request, "resource", (principal,r) -> principal.subject().equals("u-1")));
    }
    @Test void middlewareSetsIdentityWithoutThreadLocals() throws Exception {
        JwtHmac256 jwt = codec();
        String token = jwt.issue(id,Duration.ofMinutes(3));
        Request request = new Request("GET","/",Map.of(),Map.of("authorization", "Bearer " + token),"");
        new BearerAuthMiddleware(jwt).handle(request, r -> java.util.concurrent.CompletableFuture.completedFuture(Response.text("ok")));
        assertEquals("u-1", Authorization.identity(request).orElseThrow().subject());
    }
    @Test void passwordHashAndVerification() {
        PasswordHasher hasher = new PasswordHasher(100_000); // faster test only; default uses 600k.
        String hashed = hasher.hash("correct horse battery staple".toCharArray());
        assertTrue(hasher.verify("correct horse battery staple".toCharArray(), hashed));
        assertFalse(hasher.verify("wrong password".toCharArray(), hashed));
        assertFalse(hasher.verify("wrong password".toCharArray(), "$broken"));
        assertTrue(new PasswordHasher().needsRehash(hashed));
    }
}
