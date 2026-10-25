package dev.fastframe.database;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** Mutable, per-operation SQL builder; do not share between threads. */
public final class Query {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final SqlSession session;
    private final String table;
    private final List<String> whereSql = new ArrayList<>();
    private final List<Object> whereBindings = new ArrayList<>();
    private final List<String> sorting = new ArrayList<>();
    private List<String> selected = List.of("*");
    private Integer rowLimit;
    private Integer rowOffset;

    Query(SqlSession session, String tableName) {
        this.session = Objects.requireNonNull(session);
        this.table = identifier(tableName);
    }

    /** Quote identifiers, never interpolate user-provided SQL fragments. */
    private static String identifier(String value) {
        Objects.requireNonNull(value, "identifier");
        String[] parts = value.split("\\.", -1);
        StringJoiner joiner = new StringJoiner(".");
        for (String p : parts) {
            if (!IDENTIFIER.matcher(p).matches()) throw new IllegalArgumentException("Invalid SQL identifier: " + value);
            joiner.add("\"" + p + "\"");
        }
        return joiner.toString();
    }
    public Query select(String... columns) {
        if (columns == null || columns.length == 0) throw new IllegalArgumentException("Specify columns");
        List<String> result = new ArrayList<>(columns.length);
        for (String column : columns) result.add(identifier(column));
        selected = List.copyOf(result);
        return this;
    }
    public Query where(String column, Object value) {
        if (value == null) return whereNull(column);
        whereSql.add(identifier(column) + " = ?");
        whereBindings.add(value);
        return this;
    }
    public Query whereNull(String column) { whereSql.add(identifier(column) + " IS NULL"); return this; }
    public Query whereIn(String column, Collection<?> values) {
        Objects.requireNonNull(values, "values");
        String col = identifier(column);
        if (values.isEmpty()) { whereSql.add("1 = 0"); return this; }
        if (values.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("whereIn does not accept null elements");
        whereSql.add(col + " IN (" + String.join(", ", Collections.nCopies(values.size(), "?")) + ")");
        whereBindings.addAll(values);
        return this;
    }
    public Query orderBy(String column, Direction direction) {
        sorting.add(identifier(column) + " " + Objects.requireNonNull(direction).name());
        return this;
    }
    public Query limit(int value) { if (value < 1) throw new IllegalArgumentException("limit must be > 0"); rowLimit = value; return this; }
    public Query offset(int value) { if (value < 0) throw new IllegalArgumentException("offset must be >= 0"); rowOffset = value; return this; }

    public enum Direction { ASC, DESC }
    private String wherePart() { return whereSql.isEmpty() ? "" : " WHERE " + String.join(" AND ", whereSql); }
    private List<Object> selectParams() {
        List<Object> args = new ArrayList<>(whereBindings);
        if (rowLimit != null) args.add(rowLimit);
        if (rowOffset != null) args.add(rowOffset);
        return List.copyOf(args);
    }
    public String toSql() {
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", selected)).append(" FROM ").append(table).append(wherePart());
        if (!sorting.isEmpty()) sql.append(" ORDER BY ").append(String.join(", ", sorting));
        if (rowLimit != null) sql.append(" LIMIT ?");
        if (rowOffset != null) sql.append(" OFFSET ?");
        return sql.toString();
    }
    public List<Object> bindings() { return selectParams(); }
    public List<Map<String, Object>> get() {
        return session.use(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(toSql())) {
                bind(ps, selectParams());
                try (ResultSet rs = ps.executeQuery()) { return rows(rs); }
            }
        });
    }
    public Optional<Map<String, Object>> first() {
        Integer oldLimit = rowLimit;
        try { rowLimit = 1; List<Map<String, Object>> values = get(); return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0)); }
        finally { rowLimit = oldLimit; }
    }
    public long count() {
        String sql = "SELECT COUNT(*) FROM " + table + wherePart();
        return session.use(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, whereBindings);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
            }
        });
    }
    public boolean exists() {
        String sql = "SELECT 1 FROM " + table + wherePart() + " LIMIT 1";
        return session.use(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, whereBindings);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        });
    }
    public int insert(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("insert requires columns");
        List<String> columns = values.keySet().stream().map(Query::identifier).toList();
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
        return updateStatement(sql, new ArrayList<>(values.values()));
    }
    public int update(Map<String, ?> values) {
        requireFilter("update");
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) throw new IllegalArgumentException("update requires columns");
        List<String> assignments = values.keySet().stream().map(k -> identifier(k) + " = ?").toList();
        String sql = "UPDATE " + table + " SET " + String.join(", ", assignments) + wherePart();
        List<Object> args = new ArrayList<>(values.values()); args.addAll(whereBindings);
        return updateStatement(sql, args);
    }
    public int delete() {
        requireFilter("delete");
        return updateStatement("DELETE FROM " + table + wherePart(), whereBindings);
    }
    private void requireFilter(String op) { if (whereSql.isEmpty()) throw new IllegalStateException("Refusing unfiltered " + op + "; specify where(...)"); }
    private int updateStatement(String sql, List<?> params) {
        return session.use(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }
    static void bind(PreparedStatement statement, List<?> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
    }
    private static List<Map<String, Object>> rows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        int fields = md.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(fields);
            for (int i = 1; i <= fields; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
            result.add(row);
        }
        return result;
    }
}
