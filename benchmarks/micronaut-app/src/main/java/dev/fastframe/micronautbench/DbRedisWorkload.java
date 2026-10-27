package dev.fastframe.micronautbench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** The exact same SQL and key/value operations as FastFrame's workload. */
public final class DbRedisWorkload {
    private static class Holder { static final DbRedisWorkload INSTANCE = new DbRedisWorkload(); }
    public static DbRedisWorkload get() { return Holder.INSTANCE; }
    private final HikariDataSource pool;
    private final RedisClient client;
    private final StatefulRedisConnection<String,String> redis;
    private DbRedisWorkload() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(System.getenv().getOrDefault("BENCH_JDBC_URL", "jdbc:postgresql://127.0.0.1:55432/ffbench"));
        cfg.setUsername(System.getenv().getOrDefault("BENCH_DB_USER", "ffbench"));
        cfg.setPassword(System.getenv().getOrDefault("BENCH_DB_PASSWORD", "ffbench"));
        int size = Integer.parseInt(System.getenv().getOrDefault("BENCH_DB_POOL", "16"));
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(size);
        cfg.setConnectionTimeout(3_000);
        cfg.setPoolName("MicronautBenchPool");
        pool = new HikariDataSource(cfg);
        client = RedisClient.create(System.getenv().getOrDefault("BENCH_REDIS_URL", "redis://127.0.0.1:56379"));
        redis = client.connect();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            redis.close(); client.shutdown(); pool.close();
        }));
    }
    private static RuntimeException failure(SQLException error) {
        return new IllegalStateException("Database benchmark failed", error);
    }
    public Map<String,String> select() {
        try (Connection c = pool.getConnection();
             PreparedStatement stmt = c.prepareStatement("SELECT payload FROM bench_lookup WHERE id=?")) {
            stmt.setInt(1, 1);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("benchmark lookup missing");
                return Map.of("value", rs.getString(1));
            }
        } catch (SQLException e) { throw failure(e); }
    }
    private void statement(String sql, Object... params) {
        try (Connection c = pool.getConnection(); PreparedStatement stmt = c.prepareStatement(sql)) {
            for (int i=0;i<params.length;i++) stmt.setObject(i+1,params[i]);
            if (stmt.executeUpdate()!=1) throw new IllegalStateException("Expected one modified row");
        } catch (SQLException e) { throw failure(e); }
    }
    public Map<String,Boolean> insert() {
        statement("INSERT INTO bench_events (payload) VALUES (?)", "ok");
        return Map.of("ok", true);
    }
    public Map<String,Boolean> update() {
        statement("UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?", 1);
        return Map.of("ok", true);
    }
    public Map<String,Boolean> transaction() {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (int id=1;id<=2;id++) {
                    try (PreparedStatement stmt = c.prepareStatement("UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?")) {
                        stmt.setInt(1,id);
                        if (stmt.executeUpdate()!=1) throw new IllegalStateException("benchmark counter missing");
                    }
                }
                c.commit();
            } catch (Exception ex) {
                try { c.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
                throw ex;
            }
        } catch (SQLException ex) { throw failure(ex); }
        return Map.of("ok", true);
    }
    public Map<String,String> cacheGet() {
        String result = redis.sync().get("ffbench:hot");
        if (result==null) throw new IllegalStateException("benchmark cache key missing");
        return Map.of("value",result);
    }
    public Map<String,Boolean> cacheSet() {
        redis.sync().set("ffbench:set", "ok");
        return Map.of("ok",true);
    }
}
