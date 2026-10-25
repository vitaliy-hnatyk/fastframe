import dev.fastframe.auth.*;
import dev.fastframe.core.http.*;
import java.util.*;
import java.time.*;
public class AuthSmoke {
  public static void main(String[] ignored) throws Exception {
    byte[] secret = new byte[32]; new java.security.SecureRandom().nextBytes(secret);
    JwtHmac256 jwt = new JwtHmac256(secret,"issuer","aud");
    Identity user = new Identity("test-user",Set.of("admin"),Set.of("users.read"));
    String token = jwt.issue(user,Duration.ofMinutes(5));
    if (!jwt.verify(token).equals(user)) throw new AssertionError("valid JWT");
    if (!jwt.verify(token).grants("role:admin")) throw new AssertionError("role");
    try { jwt.verify(token.substring(0,token.lastIndexOf('.') + 1) + "invalid"); throw new AssertionError("tampered token accepted"); }
    catch (IllegalArgumentException expected) { }
    Request request = new Request("GET","/",Map.of(), Map.of("authorization","Bearer "+token),"");
    new BearerAuthMiddleware(jwt).handle(request, r -> java.util.concurrent.CompletableFuture.completedFuture(Response.text("ok")))
        .toCompletableFuture().join();
    if (Authorization.require(request,"users.read") != null) throw new AssertionError("permission");
    if (Authorization.require(request,"no.permission").status()!=403) throw new AssertionError("forbidden");
    if (Authorization.require(new Request("GET","/",Map.of(),Map.of(),""),null).status()!=401) throw new AssertionError("unauthorized");
    PasswordHasher passwords = new PasswordHasher(100_000);
    String hash = passwords.hash("hello-safety".toCharArray());
    if (!passwords.verify("hello-safety".toCharArray(),hash)
      || passwords.verify("wrong".toCharArray(),hash)) throw new AssertionError("password");
    System.out.println("AUTH SMOKE PASS");
  }
}
