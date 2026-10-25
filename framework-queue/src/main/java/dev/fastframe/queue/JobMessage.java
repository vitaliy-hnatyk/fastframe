package dev.fastframe.queue;

import java.util.Objects;
import java.util.UUID;

/** Immutable wire-friendly message. Never serialize executable Java objects into queues. */
public record JobMessage(UUID id, String type, String payload, int maxAttempts, long createdAtMillis) {
    public JobMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (!type.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) throw new IllegalArgumentException("Invalid job type");
        if (payload.length() > 262144) throw new IllegalArgumentException("Job payload exceeds 256K characters");
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("maxAttempts must be 1..100");
        if (createdAtMillis < 0) throw new IllegalArgumentException("Invalid creation time");
    }
}
