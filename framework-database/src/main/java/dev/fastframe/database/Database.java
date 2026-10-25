package dev.fastframe.database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/** Entry point for the blocking JDBC Query Builder. Keep calls off Netty event loops. */
public final class Database implements SqlSession {
    private final DataSource dataSource;
    public Database(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource, "dataSource"); }

    public Query table(String name) { return new Query(this, name); }

    @Override public <T> T use(SqlAction<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            return action.apply(connection);
        } catch (SQLException ex) {
            throw new DatabaseException("Database operation failed", ex);
        }
    }

    /** Raw SQL for trusted application-defined migrations; values always use placeholders. */
    public int execute(String sql, Object... args) {
        Objects.requireNonNull(sql, "sql");
        return use(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                Query.bind(statement, java.util.Arrays.asList(args));
                return statement.executeUpdate();
            }
        });
    }

    /** One JDBC connection, commit on success and rollback on failure. No async work in callback. */
    public <T> T transaction(TransactionWork<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (Connection connection = dataSource.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            Transaction transaction = new Transaction(connection);
            try {
                T result = operation.apply(transaction);
                connection.commit();
                return result;
            } catch (Exception | Error failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
                throw new DatabaseException("Transaction failed", failure);
            } finally {
                transaction.closeScope();
                try { connection.setAutoCommit(oldAutoCommit); }
                catch (SQLException ignored) { /* try-with-resources closes connection */ }
            }
        } catch (SQLException ex) {
            throw new DatabaseException("Transaction connection failed", ex);
        }
    }

    @FunctionalInterface
    public interface TransactionWork<T> { T apply(Transaction transaction) throws Exception; }
}
