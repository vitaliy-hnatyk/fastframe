package dev.fastframe.security;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class SecurityPolicyTest {
    @Test void exactOriginOnly() {
        var cors = new CorsPolicy(Set.of("https://app.example.com"),
                Set.of("GET", "POST"), Set.of("content-type", "authorization"), true, 600);
        assertTrue(cors.allowsOrigin("https://app.example.com"));
        assertFalse(cors.allowsOrigin("https://app.example.com.evil.test"));
        assertFalse(cors.allowsOrigin("http://app.example.com"));
        assertTrue(cors.allowsPreflight("POST", "Content-Type, AUTHORIZATION"));
        assertFalse(cors.allowsPreflight("DELETE", null));
        assertFalse(cors.allowsPreflight("POST", "X-Api-Key"));
        assertFalse(cors.allowsPreflight("POST", "Content-Type,"));
        for (String bad : Set.of("*", "null", "https://a.test/path", "https://u:p@a.test", "https://a.test,https://b.test"))
            assertThrows(IllegalArgumentException.class, () -> new CorsPolicy(Set.of(bad),
                    Set.of("GET"), Set.of("content-type"), false, 600));
    }

    @Test void windowRolloverAndRetryAfter() {
        AtomicLong now = new AtomicLong(1_000_000);
        var limiter = new IpRateLimiter(2, Duration.ofSeconds(10), 2, now::get);
        assertEquals(0, limiter.acquire("A"));
        assertEquals(0, limiter.acquire("A"));
        assertEquals(10, limiter.acquire("A"));
        now.addAndGet(Duration.ofSeconds(3).toNanos());
        assertEquals(7, limiter.acquire("A"));
        assertEquals(0, limiter.acquire("B"));
        // Capacity guard cannot evict active key buckets.
        assertEquals(10, limiter.acquire("C"));
        now.addAndGet(Duration.ofSeconds(8).toNanos());
        // At t=11s A's old bucket has expired, but reacquiring A fills
        // its slot again. B is still active until t=13s; C must be denied.
        assertEquals(0, limiter.acquire("A"));
        assertEquals(10, limiter.acquire("C"));
        // Once B's bucket expires, its slot becomes available to C.
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertEquals(0, limiter.acquire("C"));
    }
}
