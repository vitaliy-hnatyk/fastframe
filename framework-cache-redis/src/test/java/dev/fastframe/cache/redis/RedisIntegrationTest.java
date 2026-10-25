package dev.fastframe.cache.redis;

import dev.fastframe.cache.Cache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.time.Duration;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Optional integration test. Requires FF_TEST_REDIS_URI pointing at a disposable Redis instance. */
class RedisIntegrationTest {
    @Test void redisTtlAddAndForget() throws Exception {
        String uri = System.getenv("FF_TEST_REDIS_URI");
        Assumptions.assumeTrue(uri != null && !uri.isBlank(), "FF_TEST_REDIS_URI not configured");
        try (var backend = new RedisCacheStore(uri, "ff-test:"+ UUID.randomUUID() + ":")) {
            Cache cache = new Cache(backend);
            assertTrue(cache.add("k", "value", Duration.ofMillis(60)));
            assertFalse(cache.add("k", "other", Duration.ofSeconds(2)));
            assertEquals("value",cache.get("k").orElseThrow());
            Thread.sleep(120);
            assertFalse(cache.has("k"));
            cache.forever("key", "durable");
            assertEquals("durable", cache.get("key").orElseThrow());
            assertTrue(cache.forget("key"));
            assertFalse(cache.forget("key"));
        }
    }
}
