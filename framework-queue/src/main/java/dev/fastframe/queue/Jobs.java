package dev.fastframe.queue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Fluent builder: create, configure and explicitly enqueue (no accidental early dispatch). */
public final class Jobs {
    private final QueueStore store;
    private final Clock clock;
    public Jobs(QueueStore store) { this(store, Clock.systemUTC()); }
    public Jobs(QueueStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }
    public PendingJob dispatch(String type, String payload) { return new PendingJob(type, payload); }
    public final class PendingJob {
        private final String type, payload;
        private Duration delay = Duration.ZERO;
        private int maxAttempts = 3;
        private PendingJob(String type, String payload) {
            this.type = Objects.requireNonNull(type);
            this.payload = Objects.requireNonNull(payload);
        }
        public PendingJob delay(Duration value) {
            if (value.isNegative()) throw new IllegalArgumentException("Negative delay");
            delay = value;
            return this;
        }
        public PendingJob maxAttempts(int value) { maxAttempts = value; return this; }
        public UUID enqueue() {
            Instant now = clock.instant();
            JobMessage job = new JobMessage(UUID.randomUUID(), type, payload, maxAttempts, now.toEpochMilli());
            store.enqueue(job, now.plus(delay));
            return job.id();
        }
    }
}
