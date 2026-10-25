package dev.fastframe.database;

import java.sql.Connection;
import java.sql.SQLException;

interface SqlSession {
    <T> T use(SqlAction<T> action);
    @FunctionalInterface
    interface SqlAction<T> { T apply(Connection connection) throws SQLException; }
}
