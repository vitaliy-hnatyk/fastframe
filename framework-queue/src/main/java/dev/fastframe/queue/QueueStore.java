package dev.fastframe.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Thread-safe job store with at-least-once delivery and fenced leases. */
public interface QueueStore extends AutoCloseable {
    void enqueue(JobMessage job, Instant availableAt);
    Optional<ReservedJob> reserve(Instant now, Duration visibilityTimeout);
    boolean ack(ReservedJob job);
    boolean retry(ReservedJob job, Instant availableAt);
    boolean fail(ReservedJob job, String errorType, Instant at);
    List<FailedJob> failedJobs();
    @Override default void close() {}
}
