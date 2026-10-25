import dev.fastframe.security.*;
import java.time.Duration;
import java.util.Set;

public final class SecuritySmoke {
    public static void main(String[] args) {
        var cors = new CorsPolicy(Set.of("https://app.example.org"), Set.of("GET", "POST"),
                Set.of("content-type"), false, 300);
        if (!cors.allowsOrigin("https://app.example.org") || cors.allowsOrigin("https://evil.example.org"))
            throw new AssertionError("CORS origin mismatch");
        if (!cors.allowsPreflight("POST", "content-type") || cors.allowsPreflight("DELETE", "content-type"))
            throw new AssertionError("CORS preflight mismatch");
        var limiter = new IpRateLimiter(2, Duration.ofSeconds(5), 4);
        if (limiter.acquire("127.0.0.1") != 0 || limiter.acquire("127.0.0.1") != 0
                || limiter.acquire("127.0.0.1") <= 0)
            throw new AssertionError("limiting mismatch");
        System.out.println("Security smoke PASS");
    }
}
