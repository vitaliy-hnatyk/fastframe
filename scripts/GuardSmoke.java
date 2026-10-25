import dev.fastframe.auth.*;
import dev.fastframe.core.http.*;
import dev.fastframe.generated.FastFrameRoutes;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public class GuardSmoke {
    private static Response get(Router router, String path, String bearer) throws Exception {
        var headers = bearer == null ? Map.<String,String>of() : Map.of("authorization", "Bearer " + bearer);
        Object result = router.dispatchDirect(new Request("GET",path,Map.of(),headers,""));
        return result instanceof Response r ? r : ((CompletionStage<Response>) result).toCompletableFuture().join();
    }
    public static void main(String[] args) throws Exception {
        byte[] secret = new byte[32]; new java.security.SecureRandom().nextBytes(secret);
        var jwt = new JwtHmac256(secret, "issuer", "aud");
        Router router = new Router().use(new BearerAuthMiddleware(jwt));
        FastFrameRoutes.register(router);
        if (get(router,"/api/secure/me",null).status() != 401) throw new AssertionError("expected 401");
        String limited = jwt.issue(new Identity("guest",Set.of(),Set.of()),Duration.ofMinutes(2));
        if (get(router,"/api/secure/me",limited).status() != 200) throw new AssertionError("expected me 200");
        if (get(router,"/api/secure/users",limited).status() != 403) throw new AssertionError("expected 403");
        String permitted = jwt.issue(new Identity("user",Set.of(),Set.of("users.read")),Duration.ofMinutes(2));
        if (get(router,"/api/secure/users",permitted).status() != 200) throw new AssertionError("expected permission 200");
        if (get(router,"/api/secure/admin",permitted).status() != 403) throw new AssertionError("expected admin 403");
        String admin = jwt.issue(new Identity("admin",Set.of("admin"),Set.of()),Duration.ofMinutes(2));
        if (get(router,"/api/secure/admin",admin).status() != 200) throw new AssertionError("expected admin 200");
        if (get(router,"/api/secure/me","garbage").status() != 401) throw new AssertionError("bad token denied");
        System.out.println("ROUTE GUARDS SMOKE PASS: 401/403/200, permission, role, JWT");
    }
}
