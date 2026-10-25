package dev.fastframe.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** A Laravel-inspired, explicit String cache facade. No implicit Java object serialization. */
public final class Cache {
    private final CacheStore store;
    private final Object[] keyLocks = new Object[128];

    public Cache(CacheStore store) {
        this.store = Objects.requireNonNull(store, "store");
        for (int i = 0; i < keyLocks.length; i++) keyLocks[i] = new Object();
    }

    public Optional<String> get(String key) { return store.get(key); }
    public String getOrDefault(String key, String fallback) { return get(key).orElse(fallback); }
    public boolean has(String key) { return store.has(key); }
    public void put(String key, String value, Duration ttl) { store.put(key, value, ttl); }
    public void forever(String key, String value) { store.putForever(key, value); }
    public boolean add(String key, String value, Duration ttl) { return store.add(key, value, ttl); }
    public boolean forget(String key) { return store.forget(key); }

    /** Prevents duplicate supplier execution per key within this Cache instance. Not a distributed lock. */
    public String remember(String key, Duration ttl, Supplier<String> supplier) {
        CacheStore.key(key);
        CacheStore.ttlNanos(ttl);
        Objects.requireNonNull(supplier, "supplier");
        Optional<String> cached = get(key);
        if (cached.isPresent()) return cached.get();
        synchronized (lockFor(key)) {
            cached = get(key);
            if (cached.isPresent()) return cached.get();
            String created = CacheStore.value(supplier.get());
            store.put(key, created, ttl);
            return created;
        }
    }

    public String rememberForever(String key, Supplier<String> supplier) {
        CacheStore.key(key);
        Objects.requireNonNull(supplier, "supplier");
        Optional<String> cached = get(key);
        if (cached.isPresent()) return cached.get();
        synchronized (lockFor(key)) {
            cached = get(key);
            if (cached.isPresent()) return cached.get();
            String created = CacheStore.value(supplier.get());
            store.putForever(key, created);
            return created;
        }
    }

    /** Execute potentially blocking Redis operations on an explicitly supplied worker executor. */
    public CompletionStage<String> rememberAsync(String key, Duration ttl,
                                                 Supplier<String> supplier, Executor workerExecutor) {
        Objects.requireNonNull(workerExecutor, "workerExecutor");
        return CompletableFuture.supplyAsync(() -> remember(key, ttl, supplier), workerExecutor);
    }

    private Object lockFor(String key) {
        return keyLocks[(key.hashCode() & 0x7fffffff) % keyLocks.length];
    }
}
