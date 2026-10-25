package dev.fastframe.queue;

import java.util.Objects;
/** Receipt token fences late acknowledgements after a lease expires. */
public record ReservedJob(JobMessage message, int attempt, String token) {
    public ReservedJob {
        Objects.requireNonNull(message);
        Objects.requireNonNull(token);
        if (attempt < 1) throw new IllegalArgumentException("Invalid attempt number");
    }
}
