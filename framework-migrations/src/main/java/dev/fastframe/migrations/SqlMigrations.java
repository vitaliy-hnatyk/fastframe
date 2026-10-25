package dev.fastframe.migrations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads SQL migrations from an explicit directory. No classpath scanning. */
public final class SqlMigrations {
    private static final Pattern NAME = Pattern.compile("V([0-9]+)__([a-z][a-z0-9_]*)\\.sql");
    private SqlMigrations() {}

    public static List<SqlMigration> load(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IllegalArgumentException("Not a migration directory: " + directory);
        List<SqlMigration> result = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".sql")) continue;
                Matcher matcher = NAME.matcher(name);
                if (!matcher.matches()) throw new IllegalArgumentException("Bad migration file name: " + name);
                long version = Long.parseLong(matcher.group(1));
                result.add(SqlMigration.of(version, matcher.group(2), Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        result.sort(Comparator.comparingLong(SqlMigration::version));
        Set<Long> versions = new HashSet<>();
        for (SqlMigration migration : result)
            if (!versions.add(migration.version())) throw new IllegalArgumentException("Duplicate migration version: " + migration.version());
        return List.copyOf(result);
    }
}
