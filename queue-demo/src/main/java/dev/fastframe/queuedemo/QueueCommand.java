package dev.fastframe.queuedemo;

import dev.fastframe.queue.JobScheduler;
import dev.fastframe.queue.JobWorker;
import dev.fastframe.queue.Jobs;
import dev.fastframe.queue.redis.RedisQueueStore;
import java.time.Duration;
import java.time.ZoneId;

/** Usage: dispatch [payload], worker, scheduler, failures. Requires FF_REDIS_URI. */
public final class QueueCommand {
    private QueueCommand() {}
    public static void main(String[] args) throws Exception {
        String uri=System.getenv("FF_REDIS_URI");
        if (uri==null || uri.isBlank()) throw new IllegalStateException("Set FF_REDIS_URI first");
        String prefix=System.getenv().getOrDefault("FF_QUEUE_PREFIX","fastframe:demo:jobs");
        if (args.length==0) throw new IllegalArgumentException("Usage: dispatch|worker|scheduler|failures");
        try (var store=new RedisQueueStore(uri,prefix)) {
            Jobs jobs=new Jobs(store);
            switch(args[0]) {
                case "dispatch" -> {
                    String payload=args.length>1 ? args[1] : "Hello from a Redis-backed job";
                    var id=jobs.dispatch("demo.greeting",payload).maxAttempts(3).enqueue();
                    System.out.println("Enqueued " + id);
                }
                case "worker" -> {
                    var worker=new JobWorker(store).handle("demo.greeting",
                            payload -> System.out.println("Handled: " + payload));
                    Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
                    System.out.println("Dedicated worker running. Ctrl+C to stop.");
                    while (!Thread.currentThread().isInterrupted()) {
                        if (!worker.runOnce()) Thread.sleep(250);
                    }
                }
                case "scheduler" -> {
                    var scheduler=new JobScheduler(jobs)
                            .cron("cleanup-example","*/5 * * * *",ZoneId.of("UTC"),
                                    "demo.greeting","Scheduled hello");
                    Runtime.getRuntime().addShutdownHook(new Thread(scheduler::close));
                    System.out.println("Scheduler running (5 minute cron UTC). Run only one scheduler instance.");
                    while (!Thread.currentThread().isInterrupted()) {
                        scheduler.tick(java.time.Instant.now());
                        Thread.sleep(1000);
                    }
                }
                case "failures" -> store.failedJobs().forEach(f ->
                        System.out.printf("%s type=%s attempts=%d error=%s%n",
                                f.message().id(),f.message().type(),f.attempts(),f.errorType()));
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            }
        }
    }
}
