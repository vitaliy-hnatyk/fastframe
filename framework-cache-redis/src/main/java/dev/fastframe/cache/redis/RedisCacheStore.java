package dev.fastframe.cache.redis;

import dev.fastframe.cache.CacheStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Synchronous Redis cache backed by Lettuce, with a namespaced key prefix.
 * IMPORTANT: synchronous calls block; run on dedicated workers, never on a Netty event loop.
 * An instance owns its client and connection and MUST be closed on shutdown.
 */
public final class RedisCacheStore implements CacheStore, AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> redis;
    private final String prefix;

    /** Supply a redis:// or rediss:// URI (TLS recommended outside localhost). */
    public RedisCacheStore(String redisUri, String prefix) {
        Objects.requireNonNull(redisUri, "redisUri");
        if (!(redisUri.startsWith("redis://") || redisUri.startsWith("rediss://"))) {
            throw new IllegalArgumentException("Redis URI must start with redis:// or rediss://");
        }
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank() || prefix.length() > 128) {
            throw new IllegalArgumentException("A nonblank 1..128 character Redis namespace is required");
        }
        this.client = RedisClient.create(redisUri);
        try {
            this.connection = client.connect();
        } catch (RuntimeException failure) {
            client.shutdown();
            throw failure;
        }
        this.redis = connection.sync();
        this.connection.setTimeout(Duration.ofSeconds(3));
    }

    private String fullKey(String key) { return prefix + CacheStore.key(key); }

    @Override public Optional<String> get(String key) {
        return Optional.ofNullable(redis.get(fullKey(key)));
    }

    @Override public void put(String key, String value, Duration ttl) {
        long millis = CacheStore.ttlMillisRoundedUp(ttl);
        redis.set(fullKey(key), CacheStore.value(value), SetArgs.Builder.px(millis));
    }

    @Override public void putForever(String key, String value) {
        redis.set(fullKey(key), CacheStore.value(value));
    }

    @Override public boolean add(String key, String value, Duration ttl) {
        long millis = CacheStore.ttlMillisRoundedUp(ttl);
        // Redis SET NX PX is atomic across clients, unlike local remember() supplier locks.
        return "OK".equals(redis.set(fullKey(key), CacheStore.value(value), SetArgs.Builder.nx().px(millis)));
    }

    @Override public boolean forget(String key) {
        return redis.del(fullKey(key)) > 0;
    }

    @Override public void close() {
        try { connection.close(); } finally { client.shutdown(); }
    }
}
