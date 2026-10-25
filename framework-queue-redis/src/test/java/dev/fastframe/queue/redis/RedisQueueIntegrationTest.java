package dev.fastframe.queue.redis;

import dev.fastframe.queue.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Requires an isolated FF_TEST_REDIS_URI; no Redis needed for regular CI. */
class RedisQueueIntegrationTest {
    @Test @EnabledIfEnvironmentVariable(named="FF_TEST_REDIS_URI",matches=".+")
    void redeliveryAndFencing() {
        try(var store=new RedisQueueStore(System.getenv("FF_TEST_REDIS_URI"),"fftest:"+UUID.randomUUID())) {
            Instant now=Instant.now();
            var job=new JobMessage(UUID.randomUUID(),"job","hello",3,now.toEpochMilli());
            store.enqueue(job,now);
            var a=store.reserve(now,Duration.ofMillis(100)).orElseThrow();
            var b=store.reserve(now.plusSeconds(2),Duration.ofSeconds(20)).orElseThrow();
            assertFalse(store.ack(a));
            assertEquals(2,b.attempt());
            assertTrue(store.fail(b,"TestFailure",now.plusSeconds(2)));
            assertEquals(1,store.failedJobs().size());
        }
    }
}
