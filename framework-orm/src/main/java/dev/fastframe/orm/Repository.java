package dev.fastframe.orm;

import dev.fastframe.database.Database;
import dev.fastframe.database.Query;
import dev.fastframe.database.Transaction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Blocking, typed record repository. Call from JDBC worker threads, NEVER Netty event loops. */
public final class Repository<T, ID> {
    private final EntityMapper<T, ID> mapper;
    private final Database database;
    private final Transaction transaction;

    public Repository(Database database, EntityMapper<T, ID> mapper) {
        this.database = Objects.requireNonNull(database);
        this.transaction = null;
        this.mapper = Objects.requireNonNull(mapper);
    }
    public Repository(Transaction transaction, EntityMapper<T, ID> mapper) {
        this.database = null;
        this.transaction = Objects.requireNonNull(transaction);
        this.mapper = Objects.requireNonNull(mapper);
    }
    private Query table() { return transaction == null ? database.table(mapper.table()) : transaction.table(mapper.table()); }

    public Optional<T> find(ID id) {
        Objects.requireNonNull(id, "id");
        return table().where(mapper.idColumn(), id).first().map(mapper::fromRow);
    }
    public T findOrFail(ID id) {
        return find(id).orElseThrow(() -> new EntityNotFoundException(mapper.table(), id));
    }
    /** Always requires a limit to avoid accidentally reading unbounded production tables. */
    public List<T> get(int limit) { return table().limit(limit).get().stream().map(mapper::fromRow).toList(); }
    public List<T> where(String column, Object value, int limit) {
        return table().where(column, value).limit(limit).get().stream().map(mapper::fromRow).toList();
    }
    public long count() { return table().count(); }
    /** Explicit-ID inserts only: generated database IDs are not supported yet. */
    public int insert(T entity) {
        Objects.requireNonNull(entity);
        if (mapper.id(entity) == null) throw new IllegalArgumentException("v0.10 requires explicit non-null entity IDs");
        return table().insert(mapper.toColumns(entity));
    }
    public int update(T entity) {
        Objects.requireNonNull(entity);
        ID id = Objects.requireNonNull(mapper.id(entity), "entity ID");
        Map<String, Object> mutable = new LinkedHashMap<>(mapper.toColumns(entity));
        mutable.remove(mapper.idColumn());
        if (mutable.isEmpty()) throw new IllegalArgumentException("Entity has no updatable columns");
        return table().where(mapper.idColumn(), id).update(mutable);
    }
    public int delete(ID id) { return table().where(mapper.idColumn(), Objects.requireNonNull(id)).delete(); }
    /** Convenience insert-or-update based on prior existence; NOT atomic under concurrent writers. */
    public int save(T entity) {
        Objects.requireNonNull(entity);
        ID id = Objects.requireNonNull(mapper.id(entity), "entity ID");
        return find(id).isPresent() ? update(entity) : insert(entity);
    }
}
