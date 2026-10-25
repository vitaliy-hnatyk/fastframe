package dev.fastframe.orm;
import java.util.Map;
/** Generated at compile time (or implemented manually). No reflective access per row. */
public interface EntityMapper<T, ID> {
    String table();
    String idColumn();
    ID id(T entity);
    T fromRow(Map<String, Object> row);
    /** Include ID column; the v0.10 MVP uses explicit application-assigned IDs. */
    Map<String, Object> toColumns(T entity);
}
