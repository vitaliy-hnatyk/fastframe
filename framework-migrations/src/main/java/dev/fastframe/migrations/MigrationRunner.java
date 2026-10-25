package dev.fastframe.migrations;

import dev.fastframe.database.Database;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Apply versioned migrations serially, in a single process. For PostgreSQL, DDL can be transactional. */
public final class MigrationRunner {
    private final Database database;
    public MigrationRunner(Database database) { this.database = Objects.requireNonNull(database); }
    public record Result(int applied, int skipped) {}

    public Result migrate(List<SqlMigration> migrations) {
        Objects.requireNonNull(migrations, "migrations");
        List<SqlMigration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingLong(SqlMigration::version));
        Set<Long> seen = new HashSet<>();
        for (SqlMigration migration : sorted)
            if (!seen.add(migration.version())) throw new IllegalArgumentException("Duplicate migration version " + migration.version());

        database.execute("CREATE TABLE IF NOT EXISTS \"fastframe_schema_migrations\" (" +
                "\"version\" BIGINT PRIMARY KEY, \"description\" VARCHAR(120) NOT NULL, " +
                "\"checksum\" VARCHAR(64) NOT NULL, \"installed_on\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        Map<Long, Map<String, Object>> applied = new HashMap<>();
        long maxApplied = 0;
        for (var row : database.table("fastframe_schema_migrations").get()) {
            long version = ((Number) row.get("version")).longValue();
            applied.put(version, row);
            maxApplied = Math.max(version, maxApplied);
        }
        // Fail fast on altered migrations or missing historical migration files.
        for (Long version : applied.keySet()) {
            if (!seen.contains(version)) throw new IllegalStateException("Applied migration V" + version + " missing from migration set");
        }
        int skipped = 0;
        for (SqlMigration migration : sorted) {
            var existing = applied.get(migration.version());
            if (existing != null) {
                if (!migration.checksum().equals(existing.get("checksum")) ||
                        !migration.description().equals(existing.get("description")))
                    throw new IllegalStateException("Migration V" + migration.version() + " has changed since application");
                skipped++;
            } else if (migration.version() <= maxApplied) {
                throw new IllegalStateException("Out-of-order migration V" + migration.version());
            }
        }
        int installed = 0;
        for (SqlMigration migration : sorted) {
            if (applied.containsKey(migration.version())) continue;
            database.transaction(tx -> {
                tx.execute(migration.sql());
                tx.table("fastframe_schema_migrations").insert(Map.of(
                        "version", migration.version(), "description", migration.description(),
                        "checksum", migration.checksum()));
                return null;
            });
            installed++;
        }
        return new Result(installed, skipped);
    }
}
