package dev.fastframe.migrations;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** One SQL statement per migration; version must strictly increase. */
public record SqlMigration(long version, String description, String sql, String checksum) {
    public SqlMigration {
        if (version <= 0) throw new IllegalArgumentException("Migration version must be positive");
        if (description == null || !description.matches("[a-z][a-z0-9_]{0,119}"))
            throw new IllegalArgumentException("Invalid migration description: " + description);
        Objects.requireNonNull(sql, "sql");
        if (sql.isBlank()) throw new IllegalArgumentException("SQL migration cannot be empty");
        if (checksum == null || !checksum.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid migration checksum");
    }
    public static SqlMigration of(long version, String description, String sql) {
        return new SqlMigration(version, description, sql, sha256(sql));
    }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
