package dev.fastframe.benchmark;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.fastframe.cache.redis.RedisCacheStore;
import dev.fastframe.database.Database;

import java.util.Map;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Real PostgreSQL/JDBC and Redis benchmark; initialized only for DB/cache endpoints.
 * Blocking IO is offloaded from Netty event loop; explicit bounded executor avoids
 * silently piling up thousands of requests in a saturated connection pool.
 */
public final class DbRedisWorkload {
    private static class Holder { static final DbRedisWorkload INSTANCE = new DbRedisWorkload(); }
    public static DbRedisWorkload get() { return Holder.INSTANCE; }
    private final HikariDataSource pool;
    private final Database database;
    private final RedisCacheStore cache;
    private final ThreadPoolExecutor workers;
    private DbRedisWorkload() {
        int poolSize = positive("BENCH_DB_POOL", 16);
        int threads = positive("BENCH_IO_WORKERS", 32);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv().getOrDefault("BENCH_JDBC_URL", "jdbc:postgresql://127.0.0.1:55432/ffbench"));
        config.setUsername(System.getenv().getOrDefault("BENCH_DB_USER", "ffbench"));
        config.setPassword(System.getenv().getOrDefault("BENCH_DB_PASSWORD", "ffbench"));
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize);
        config.setConnectionTimeout(3_000);
        config.setPoolName("FastFrameBenchPool");
        pool = new HikariDataSource(config);
        database = new Database(pool);
        cache = new RedisCacheStore(System.getenv().getOrDefault("BENCH_REDIS_URL", "redis://127.0.0.1:56379"), "ffbench:");
        workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4096), runnable -> {
                    Thread t = new Thread(runnable, "ff-bench-blocking"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            workers.shutdownNow();
            cache.close();
            pool.close();
        }, "ff-bench-close"));
    }
    private static int positive(String key, int fallback) {
        int value = Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(fallback)));
        if (value < 1 || value > 512) throw new IllegalArgumentException(key + " outside 1..512");
        return value;
    }
    public <T> CompletionStage<T> submit(Supplier<T> action) {
        return CompletableFuture.supplyAsync(action, workers);
    }
    public Map<String, String> select() {
        return database.use(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT payload FROM bench_lookup WHERE id=?")) {
                ps.setInt(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("benchmark lookup missing");
                    return Map.of("value", rs.getString(1));
                }
            }
        });
    }
    public Map<String, Boolean> insert() {
        database.execute("INSERT INTO bench_events (payload) VALUES (?)", "ok");
        return Map.of("ok", true);
    }
    public Map<String, Boolean> update() {
        if (database.execute("UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?", 1) != 1)
            throw new IllegalStateException("benchmark counter missing");
        return Map.of("ok", true);
    }
    public Map<String, Boolean> transaction() {
        database.transaction(tx -> {
            if (tx.execute("UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?", 1) != 1 ||
                tx.execute("UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?", 2) != 1)
                throw new IllegalStateException("benchmark counter missing");
            return null;
        });
        return Map.of("ok", true);
    }
    public Map<String, String> cacheGet() {
        return Map.of("value", cache.get("hot").orElseThrow(() ->
                new IllegalStateException("benchmark cache key missing")));
    }
    public Map<String, Boolean> cacheSet() {
        cache.putForever("set", "ok");
        return Map.of("ok", true);
    }
}
