package dev.fastframe.orm;
public final class EntityNotFoundException extends RuntimeException {
    public EntityNotFoundException(String table, Object id) {
        super("No entity in " + table + " with id " + id);
    }
}
