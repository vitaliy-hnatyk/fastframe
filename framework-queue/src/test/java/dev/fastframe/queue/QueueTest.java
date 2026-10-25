package dev.fastframe.queue;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QueueTest {
    @Test void dispatchRetryAndFail() {
        Clock clock=Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"),ZoneOffset.UTC);
        var store=new InMemoryQueueStore(4);
        var jobs=new Jobs(store,clock);
        jobs.dispatch("email.send","email body").maxAttempts(2).enqueue();
        AtomicInteger calls=new AtomicInteger();
        var worker=new JobWorker(store,clock,Duration.ofMinutes(1),Duration.ZERO,Duration.ZERO)
                .handle("email.send",payload -> { calls.incrementAndGet(); throw new IllegalStateException("sensitive"); });
        assertTrue(worker.runOnce());
        assertTrue(worker.runOnce());
        assertFalse(worker.runOnce());
        assertEquals(2,calls.get());
        assertEquals(1,store.failedJobs().size());
        assertEquals("IllegalStateException",store.failedJobs().getFirst().errorType());
    }
    @Test void acknowledgesSuccessfulJobAndHonorsDelay() {
        Clock clock=Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"),ZoneOffset.UTC);
        var store=new InMemoryQueueStore(4);
        new Jobs(store,clock).dispatch("ping","hello").delay(Duration.ofSeconds(2)).enqueue();
        var worker=new JobWorker(store,clock,Duration.ofMinutes(1),Duration.ZERO,Duration.ZERO)
                .handle("ping",p -> assertEquals("hello",p));
        assertFalse(worker.runOnce());
        assertEquals(1,store.pendingCount());
        var claimed=store.reserve(clock.instant().plusSeconds(3),Duration.ofSeconds(10)).orElseThrow();
        assertTrue(store.ack(claimed));
        assertEquals(0,store.pendingCount());
    }
    @Test void staleLeaseCannotAckAnotherClaim() {
        var store=new InMemoryQueueStore(2);
        Instant now=Instant.now();
        store.enqueue(new JobMessage(java.util.UUID.randomUUID(),"job","body",3,now.toEpochMilli()),now);
        var first=store.reserve(now,Duration.ofSeconds(1)).orElseThrow();
        var second=store.reserve(now.plusSeconds(2),Duration.ofSeconds(1)).orElseThrow();
        assertFalse(store.ack(first));
        assertEquals(2,second.attempt());
        assertTrue(store.ack(second));
    }
    @Test void codecRoundTripAndFailure() {
        var message=new JobMessage(java.util.UUID.randomUUID(),"a.b","Unicode π 🐈",3,123L);
        assertEquals(message,JobCodec.decode(JobCodec.encode(message)));
        var failed=new FailedJob(message,2,"Exception",Instant.ofEpochMilli(1234));
        assertEquals(failed,JobCodec.decodeFailure(JobCodec.encodeFailure(failed)));
    }
    @Test void cronAndSchedulerDeduplicate() {
        var store=new InMemoryQueueStore(4);
        var jobs=new Jobs(store);
        var scheduler=new JobScheduler(jobs).cron("nightly","0 2 * * *",ZoneId.of("UTC"),"cleanup","done");
        Instant now=Instant.parse("2026-10-24T02:00:00Z");
        assertEquals(1,scheduler.tick(now));
        assertEquals(0,scheduler.tick(now.plusSeconds(10)));
        assertEquals(1,store.pendingCount());
        assertTrue(CronSchedule.parse("*/5 * * * *").matches(now.atZone(ZoneOffset.UTC)));
        assertFalse(CronSchedule.parse("*/5 * * * *").matches(now.plusSeconds(60).atZone(ZoneOffset.UTC)));
        assertThrows(IllegalArgumentException.class,()->CronSchedule.parse("99 * * * *"));
    }
}
