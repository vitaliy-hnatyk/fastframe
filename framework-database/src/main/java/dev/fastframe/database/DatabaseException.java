package dev.fastframe.database;

/** Unchecked wrapper for JDBC failures. Do not echo SQL exception messages to HTTP clients. */
public final class DatabaseException extends RuntimeException {
    public DatabaseException(String message, Throwable cause) { super(message, cause); }
}
