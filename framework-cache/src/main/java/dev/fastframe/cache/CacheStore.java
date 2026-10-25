package dev.fastframe.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Minimal, portable string-based cache backend. Missing values are distinct from empty strings. */
public interface CacheStore {
    Optional<String> get(String key);
    void put(String key, String value, Duration ttl);
    void putForever(String key, String value);
    /** Atomically insert only when absent (or expired). Returns true when inserted. */
    boolean add(String key, String value, Duration ttl);
    boolean forget(String key);

    default boolean has(String key) { return get(key).isPresent(); }

    static String key(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank() || key.length() > 1024 || key.indexOf((char) 0) >= 0) {
            throw new IllegalArgumentException("Cache key must have 1..1024 nonblank characters without NUL");
        }
        return key;
    }

    static String value(String value) { return Objects.requireNonNull(value, "value"); }

    static long ttlNanos(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("TTL must be positive");
        try {
            long nanos = ttl.toNanos();
            // Monotonic signed deadline arithmetic stays safe for durations below this limit.
            if (nanos <= 0 || nanos >= Long.MAX_VALUE / 4) throw new IllegalArgumentException("TTL too large");
            return nanos;
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("TTL too large", ex);
        }
    }

    static long ttlMillisRoundedUp(Duration ttl) {
        long ns = ttlNanos(ttl);
        return (ns / 1_000_000L) + (ns % 1_000_000L == 0 ? 0 : 1);
    }
}
