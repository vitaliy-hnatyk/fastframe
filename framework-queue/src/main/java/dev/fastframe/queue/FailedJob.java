package dev.fastframe.queue;
import java.time.Instant;
/** Failed jobs require explicit operator action; not retried automatically. */
public record FailedJob(JobMessage message, int attempts, String errorType, Instant failedAt) {}
