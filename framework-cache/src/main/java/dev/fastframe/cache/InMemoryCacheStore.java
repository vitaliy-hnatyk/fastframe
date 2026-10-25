package dev.fastframe.cache;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Bounded LRU in-memory store. Operations are synchronized: simple and safe, but not a
 * zero-contention distributed cache. Time is monotonic, not wall-clock based.
 */
public final class InMemoryCacheStore implements CacheStore {
    private record Entry(String value, long deadline, boolean forever) {
        boolean expired(long now) { return !forever && now - deadline >= 0; }
    }

    private final int capacity;
    private final LongSupplier ticker;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public InMemoryCacheStore() { this(10_000); }
    public InMemoryCacheStore(int capacity) { this(capacity, System::nanoTime); }

    // Package-private for deterministic, timer-free expiry tests.
    InMemoryCacheStore(int capacity, LongSupplier ticker) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.ticker = java.util.Objects.requireNonNull(ticker, "ticker");
    }

    @Override public synchronized Optional<String> get(String key) {
        Entry e = liveEntry(CacheStore.key(key));
        return e == null ? Optional.empty() : Optional.of(e.value());
    }

    @Override public synchronized void put(String key, String value, Duration ttl) {
        long nanos = CacheStore.ttlNanos(ttl);
        String validKey = CacheStore.key(key);
        entries.put(validKey, new Entry(CacheStore.value(value), ticker.getAsLong() + nanos, false));
        evictToCapacity();
    }

    @Override public synchronized void putForever(String key, String value) {
        entries.put(CacheStore.key(key), new Entry(CacheStore.value(value), 0L, true));
        evictToCapacity();
    }

    @Override public synchronized boolean add(String key, String value, Duration ttl) {
        String validKey = CacheStore.key(key);
        long nanos = CacheStore.ttlNanos(ttl);
        CacheStore.value(value);
        if (liveEntry(validKey) != null) return false;
        entries.put(validKey, new Entry(value, ticker.getAsLong() + nanos, false));
        evictToCapacity();
        return true;
    }

    @Override public synchronized boolean forget(String key) {
        String validKey = CacheStore.key(key);
        if (liveEntry(validKey) == null) return false;
        entries.remove(validKey);
        return true;
    }

    public synchronized int size() {
        long now = ticker.getAsLong();
        entries.values().removeIf(e -> e.expired(now));
        return entries.size();
    }

    private Entry liveEntry(String key) {
        Entry e = entries.get(key);
        if (e != null && e.expired(ticker.getAsLong())) {
            entries.remove(key);
            return null;
        }
        return e;
    }

    private void evictToCapacity() {
        while (entries.size() > capacity) {
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            it.next();
            it.remove();
        }
    }
}
