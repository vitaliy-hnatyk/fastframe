package dev.fastframe.database;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/** Minimal convenience DataSource, no pooling. For production supply a pooled DataSource. */
public final class DriverManagerDataSource implements DataSource {
    private final String jdbcUrl;
    private final String user;
    private final String password;
    public DriverManagerDataSource(String jdbcUrl, String user, String password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.user = user; this.password = password;
    }
    @Override public Connection getConnection() throws SQLException {
        if (user == null) return DriverManager.getConnection(jdbcUrl);
        return DriverManager.getConnection(jdbcUrl, user, password);
    }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
    @Override public PrintWriter getLogWriter() throws SQLException { return DriverManager.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { DriverManager.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { DriverManager.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return DriverManager.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("No parent logger");
    }
    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface.getName());
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}
