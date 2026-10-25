package dev.fastframe.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded store for single-JVM demos. Volatile: NOT a durable cross-process queue. */
public final class InMemoryQueueStore implements QueueStore {
    private enum State { READY, IN_FLIGHT }
    private static final class Entry {
        final JobMessage message;
        long availableAt;
        long leaseUntil;
        int attempt;
        State state = State.READY;
        String token;
        Entry(JobMessage job, long ready) { this.message = job; this.availableAt = ready; }
    }
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final List<FailedJob> failed = new ArrayList<>();
    private final int maxPending;

    public InMemoryQueueStore(int maxPending) {
        if (maxPending < 1) throw new IllegalArgumentException("maxPending must be positive");
        this.maxPending = maxPending;
    }
    @Override public synchronized void enqueue(JobMessage job, Instant availableAt) {
        Objects.requireNonNull(job); Objects.requireNonNull(availableAt);
        if (entries.size() >= maxPending) throw new IllegalStateException("Queue is full");
        if (entries.putIfAbsent(job.id(), new Entry(job, availableAt.toEpochMilli())) != null)
            throw new IllegalArgumentException("Duplicate job id");
    }
    @Override public synchronized Optional<ReservedJob> reserve(Instant now, Duration visibilityTimeout) {
        Objects.requireNonNull(now);
        if (visibilityTimeout.isZero() || visibilityTimeout.isNegative())
            throw new IllegalArgumentException("visibilityTimeout must be positive");
        long timestamp = now.toEpochMilli();
        // At-least-once: leases eventually become available again after a worker crash.
        for (Entry entry : entries.values()) {
            if (entry.state == State.IN_FLIGHT && entry.leaseUntil <= timestamp) {
                entry.state = State.READY;
                entry.availableAt = timestamp;
                entry.token = null;
            }
        }
        Entry selected = entries.values().stream()
            .filter(e -> e.state == State.READY && e.availableAt <= timestamp)
            .min(Comparator.comparingLong(e -> e.availableAt)).orElse(null);
        if (selected == null) return Optional.empty();
        selected.state = State.IN_FLIGHT;
        selected.leaseUntil = Math.addExact(timestamp, visibilityTimeout.toMillis());
        selected.token = UUID.randomUUID().toString();
        selected.attempt++;
        return Optional.of(new ReservedJob(selected.message, selected.attempt, selected.token));
    }
    private Entry claimed(ReservedJob receipt) {
        Entry entry = entries.get(receipt.message().id());
        return entry != null && entry.state == State.IN_FLIGHT && receipt.token().equals(entry.token)
              && receipt.attempt() == entry.attempt ? entry : null;
    }
    @Override public synchronized boolean ack(ReservedJob receipt) {
        if (claimed(receipt) == null) return false;
        entries.remove(receipt.message().id());
        return true;
    }
    @Override public synchronized boolean retry(ReservedJob receipt, Instant availableAt) {
        Objects.requireNonNull(availableAt);
        Entry entry = claimed(receipt);
        if (entry == null) return false;
        entry.state = State.READY;
        entry.token = null;
        entry.availableAt = availableAt.toEpochMilli();
        return true;
    }
    @Override public synchronized boolean fail(ReservedJob receipt, String errorType, Instant at) {
        Entry entry = claimed(receipt);
        if (entry == null) return false;
        entries.remove(receipt.message().id());
        failed.add(new FailedJob(entry.message, entry.attempt, errorType, at));
        return true;
    }
    @Override public synchronized List<FailedJob> failedJobs() { return List.copyOf(failed); }
    public synchronized int pendingCount() { return entries.size(); }
}
