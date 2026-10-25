package dev.fastframe.queue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A blocking dedicated worker; never call runOnce() on a Netty event loop. */
public final class JobWorker implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(JobWorker.class.getName());
    private final QueueStore store;
    private final Clock clock;
    private final Duration visibilityTimeout;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public JobWorker(QueueStore store) {
        this(store, Clock.systemUTC(), Duration.ofSeconds(60), Duration.ofMillis(500), Duration.ofSeconds(30));
    }
    public JobWorker(QueueStore store, Clock clock, Duration visibilityTimeout,
                     Duration baseBackoff, Duration maxBackoff) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        if (visibilityTimeout.isZero() || visibilityTimeout.isNegative() || visibilityTimeout.toMillis() < 1
                || baseBackoff.isNegative() || maxBackoff.isNegative() || maxBackoff.compareTo(baseBackoff) < 0)
            throw new IllegalArgumentException("Invalid worker durations");
        this.visibilityTimeout = visibilityTimeout;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
    }
    public JobWorker handle(String type, JobHandler handler) {
        if (running.get()) throw new IllegalStateException("Register handlers before starting");
        if (handlers.putIfAbsent(type, Objects.requireNonNull(handler)) != null)
            throw new IllegalArgumentException("Duplicate handler: " + type);
        return this;
    }
    /** Processes one available job, false when queue is empty. Suitable for deterministic tests. */
    public boolean runOnce() {
        var reserved = store.reserve(clock.instant(), visibilityTimeout);
        if (reserved.isEmpty()) return false;
        ReservedJob receipt = reserved.get();
        JobMessage job = receipt.message();
        JobHandler handler = handlers.get(job.type());
        if (receipt.attempt() > job.maxAttempts()) {
            store.fail(receipt, "DeliveryLimitExceeded", clock.instant());
            return true;
        }
        if (handler == null) {
            store.fail(receipt, "UnknownJobType", clock.instant());
            return true;
        }
        try {
            handler.handle(job.payload());
            if (!store.ack(receipt)) LOG.warning("Lease lost while acknowledging " + job.id());
        } catch (Exception failure) {
            String error = failure.getClass().getSimpleName(); // Don't store exception messages (secrets).
            if (receipt.attempt() >= job.maxAttempts()) {
                if (!store.fail(receipt, error, clock.instant())) LOG.warning("Lease lost for " + job.id());
            } else {
                long exponent = Math.min(receipt.attempt() - 1, 30);
                long exponential = baseBackoff.toMillis() * (1L << exponent);
                long delay = Math.min(maxBackoff.toMillis(), exponential);
                if (!store.retry(receipt, clock.instant().plusMillis(delay)))
                    LOG.warning("Lease lost while retrying " + job.id());
            }
            LOG.log(Level.WARNING, "Job failed: type={0} attempt={1} error={2}",
                    new Object[] { job.type(), receipt.attempt(), error });
        }
        return true;
    }
    /** Starts a single separate non-daemon worker thread. Call close() during shutdown. */
    public synchronized void start(Duration idleSleep) {
        if (idleSleep.isZero() || idleSleep.isNegative()) throw new IllegalArgumentException("idleSleep");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Already running");
        thread = new Thread(() -> {
            while (running.get()) {
                try {
                    if (!runOnce()) Thread.sleep(idleSleep.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException failure) {
                    LOG.log(Level.WARNING, "Queue backend failure", failure);
                    try { Thread.sleep(idleSleep.toMillis()); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }, "fastframe-queue-worker");
        thread.start();
    }
    @Override public synchronized void close() {
        running.set(false);
        if (thread != null) thread.interrupt();
    }
}
