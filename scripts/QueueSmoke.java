import dev.fastframe.queue.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free Java 21 regression smoke for the in-memory queue and scheduler. */
public final class QueueSmoke {
    private static void check(boolean value, String text) {
        if (!value) throw new AssertionError(text);
    }
    public static void main(String[] args) {
        Instant t=Instant.parse("2026-10-24T02:00:00Z");
        Clock clock=Clock.fixed(t,ZoneOffset.UTC);
        InMemoryQueueStore store=new InMemoryQueueStore(10);
        Jobs jobs=new Jobs(store,clock);
        AtomicInteger runs=new AtomicInteger();
        JobWorker worker=new JobWorker(store,clock,Duration.ofSeconds(5),Duration.ZERO,Duration.ZERO)
                .handle("welcome", payload -> { check(payload.equals("hello"),"payload"); runs.incrementAndGet(); });
        jobs.dispatch("welcome","hello").maxAttempts(3).enqueue();
        check(worker.runOnce(),"message claimed");
        check(runs.get()==1 && store.pendingCount()==0,"ack");
        jobs.dispatch("welcome","hello").delay(Duration.ofMinutes(5)).enqueue();
        check(!worker.runOnce(),"delayed job not ready");
        var due=store.reserve(t.plusSeconds(301),Duration.ofSeconds(5)).orElseThrow();
        check(store.ack(due),"delayed ack");
        var direct=new JobMessage(UUID.randomUUID(),"welcome","hello",3,t.toEpochMilli());
        store.enqueue(direct,t);
        var first=store.reserve(t,Duration.ofSeconds(1)).orElseThrow();
        var second=store.reserve(t.plusSeconds(2),Duration.ofSeconds(1)).orElseThrow();
        check(!store.ack(first),"lease fence");
        check(second.attempt()==2 && store.ack(second),"redelivery");
        var copy=JobCodec.decode(JobCodec.encode(direct));
        check(copy.equals(direct),"codec");
        var bad=new JobMessage(UUID.randomUUID(),"broken","x",2,t.toEpochMilli());
        store.enqueue(bad,t);
        JobWorker fails=new JobWorker(store,clock,Duration.ofSeconds(10),Duration.ZERO,Duration.ZERO)
                .handle("broken", payload -> { throw new IllegalStateException("secret info"); });
        check(fails.runOnce() && fails.runOnce(),"retries");
        check(store.failedJobs().size()==1 && store.failedJobs().getFirst().attempts()==2,"failed job");
        check(!store.failedJobs().getFirst().errorType().contains("secret"),"error sanitation");
        JobScheduler scheduler=new JobScheduler(jobs).daily("cleanup","02:00",ZoneOffset.UTC,"welcome","hello");
        check(scheduler.tick(t)==1 && scheduler.tick(t.plusSeconds(5))==0,"scheduler dedup");
        check(CronSchedule.parse("*/5 * * * *").matches(t.atZone(ZoneOffset.UTC)),"cron match");
        check(!CronSchedule.parse("*/5 * * * *").matches(t.plusSeconds(60).atZone(ZoneOffset.UTC)),"cron nonmatch");
        System.out.println("FastFrame Queue smoke PASS");
    }
}
