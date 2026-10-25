package dev.fastframe.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Only usable within Database.transaction callback, on the calling thread. */
public final class Transaction implements SqlSession {
    private final Connection connection;
    private final Thread owner;
    private boolean active = true;
    Transaction(Connection connection) { this.connection = Objects.requireNonNull(connection); this.owner = Thread.currentThread(); }
    public Query table(String name) { verify(); return new Query(this, name); }
    private void verify() {
        if (!active || Thread.currentThread() != owner) {
            throw new IllegalStateException("Transaction must be used inside its callback on its original thread");
        }
    }
    /** Execute one trusted migration SQL statement on the current transaction connection. */
    public int execute(String sql, Object... args) {
        Objects.requireNonNull(sql, "sql");
        return use(conn -> {
            try (var statement = conn.prepareStatement(sql)) {
                Query.bind(statement, java.util.Arrays.asList(args));
                return statement.executeUpdate();
            }
        });
    }
    void closeScope() { active = false; }
    @Override public <T> T use(SqlAction<T> action) {
        verify();
        try { return action.apply(connection); }
        catch (SQLException ex) { throw new DatabaseException("Transactional SQL operation failed", ex); }
    }
}
