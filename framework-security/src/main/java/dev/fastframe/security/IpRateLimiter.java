package dev.fastframe.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Bounded fixed-window, process-local limiter. Fail-closed at key capacity. No proxy trust. */
public final class IpRateLimiter {
    private final int requestsPerWindow;
    private final int maxKeys;
    private final long windowNanos;
    private final LongSupplier clock;
    private final Map<String, Entry> clients = new HashMap<>();

    private static final class Entry {
        long expires;
        int hits;
        Entry(long expires) { this.expires = expires; }
    }
    public IpRateLimiter(int requestsPerWindow, Duration window, int maxKeys) {
        this(requestsPerWindow, window, maxKeys, System::nanoTime);
    }
    // Package scope for deterministic unit tests.
    IpRateLimiter(int requestsPerWindow, Duration window, int maxKeys, LongSupplier clock) {
        if (requestsPerWindow < 1 || maxKeys < 1) throw new IllegalArgumentException("Positive limits required");
        Objects.requireNonNull(window);
        if (window.isNegative() || window.isZero() || window.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Window must be between 1ns and 1 day");
        this.requestsPerWindow = requestsPerWindow;
        this.maxKeys = maxKeys;
        this.windowNanos = window.toNanos();
        this.clock = Objects.requireNonNull(clock);
    }

    /** Return 0 if allowed, or Retry-After seconds if throttled. Thread-safe. */
    public synchronized long acquire(String client) {
        Objects.requireNonNull(client);
        long now = clock.getAsLong();
        Entry entry = clients.get(client);
        if (entry != null && now - entry.expires >= 0) { clients.remove(client); entry = null; }
        if (entry == null) {
            if (clients.size() >= maxKeys) {
                for (Iterator<Map.Entry<String, Entry>> it = clients.entrySet().iterator(); it.hasNext();) {
                    if (now - it.next().getValue().expires >= 0) it.remove();
                }
                // Don't evict active client state; it would let an attacker reset the limiter.
                if (clients.size() >= maxKeys) return Math.max(1L, (windowNanos + 999_999_999L) / 1_000_000_000L);
            }
            entry = new Entry(now + windowNanos);
            clients.put(client, entry);
        }
        if (entry.hits < requestsPerWindow) { entry.hits++; return 0; }
        return Math.max(1L, (entry.expires - now + 999_999_999L) / 1_000_000_000L);
    }
}
