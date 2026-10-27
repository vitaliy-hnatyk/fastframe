package dev.fastframe.testing;

import dev.fastframe.database.Database;
import dev.fastframe.database.DriverManagerDataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Unique in-memory database per fixture. refresh() drops all objects; never point at production. */
public final class H2DatabaseFixture implements AutoCloseable {
    private final String url;
    private final Database database;
    private boolean closed;
    public H2DatabaseFixture() {
        // Query quotes identifiers (e.g. "users"); H2 normally folds unquoted
        // CREATE TABLE users to USERS, which is a different identifier.
        // Fold unquoted DDL to lowercase so test migrations match Query's SQL.
        url = "jdbc:h2:mem:ff_test_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        database = new Database(new DriverManagerDataSource(url, "sa", ""));
    }
    public Database database() { if (closed) throw new IllegalStateException("Fixture is closed"); return database; }
    /** Recreate schema from trusted test SQL. Do not pass user-supplied SQL. */
    public void refresh(String... statements) {
        if (closed) throw new IllegalStateException("Fixture is closed");
        database.execute("DROP ALL OBJECTS");
        for (String statement : Objects.requireNonNull(statements)) {
            if (statement == null || statement.isBlank()) throw new IllegalArgumentException("Empty schema statement");
            database.execute(statement);
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        } catch (SQLException ex) { throw new IllegalStateException("Could not clean test database", ex); }
    }
}
