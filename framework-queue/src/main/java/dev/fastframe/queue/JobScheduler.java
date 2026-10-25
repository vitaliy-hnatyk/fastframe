package dev.fastframe.queue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Cron scheduler. Run ONE scheduler per queue namespace (no distributed leader lock yet). */
public final class JobScheduler implements AutoCloseable {
    private static final Logger LOG=Logger.getLogger(JobScheduler.class.getName());
    private record Scheduled(String name, CronSchedule cron, ZoneId zone, String type, String payload) {}
    private final List<Scheduled> jobs = new ArrayList<>();
    private final Map<String,Long> lastMinute = new HashMap<>();
    private final Jobs publisher;
    private final Clock clock;
    private final AtomicBoolean running=new AtomicBoolean();
    private Thread thread;
    public JobScheduler(Jobs publisher) { this(publisher,Clock.systemUTC()); }
    public JobScheduler(Jobs publisher, Clock clock) {
        this.publisher=Objects.requireNonNull(publisher); this.clock=Objects.requireNonNull(clock);
    }
    public synchronized JobScheduler cron(String name, String pattern, ZoneId zone, String type, String payload) {
        if (running.get()) throw new IllegalStateException("Cannot configure running scheduler");
        Objects.requireNonNull(name); Objects.requireNonNull(zone);
        if (jobs.stream().anyMatch(j -> j.name.equals(name))) throw new IllegalArgumentException("Duplicate schedule");
        jobs.add(new Scheduled(name, CronSchedule.parse(pattern), zone, type, payload));
        return this;
    }
    public JobScheduler everyMinute(String name, String type, String payload) {
        return cron(name,"* * * * *",ZoneId.of("UTC"),type,payload);
    }
    public JobScheduler daily(String name,String hhmm,ZoneId zone,String type,String payload) {
        if (!hhmm.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException("Time HH:mm expected");
        String[] parts=hhmm.split(":");
        return cron(name,Integer.parseInt(parts[1])+" "+Integer.parseInt(parts[0])+" * * *",zone,type,payload);
    }
    /** A scheduler tick publishes each task at most once per UTC minute within this process. */
    public synchronized int tick(Instant now) {
        long minute=Math.floorDiv(now.getEpochSecond(),60);
        int dispatched=0;
        for (Scheduled task:jobs) {
            ZonedDateTime local=now.atZone(task.zone);
            if (task.cron.matches(local) && !Objects.equals(lastMinute.get(task.name),minute)) {
                publisher.dispatch(task.type,task.payload).enqueue();
                lastMinute.put(task.name,minute);
                dispatched++;
            }
        }
        return dispatched;
    }
    public synchronized void start(Duration pollInterval) {
        if (pollInterval.isNegative() || pollInterval.isZero()) throw new IllegalArgumentException("pollInterval");
        if (!running.compareAndSet(false,true)) throw new IllegalStateException("Already started");
        thread=new Thread(() -> {
            while (running.get()) {
                try { tick(clock.instant()); Thread.sleep(pollInterval.toMillis()); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch(RuntimeException e) {
                    LOG.log(Level.WARNING,"Scheduler tick failed",e);
                    try { Thread.sleep(pollInterval.toMillis()); } catch(InterruptedException stop) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        },"fastframe-queue-scheduler");
        thread.start();
    }
    @Override public synchronized void close() {
        running.set(false);
        if(thread!=null) thread.interrupt();
    }
}
