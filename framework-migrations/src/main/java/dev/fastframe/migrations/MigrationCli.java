package dev.fastframe.migrations;
import dev.fastframe.database.Database;
import dev.fastframe.database.DriverManagerDataSource;
import java.nio.file.Path;

/** Run: FF_DB_URL=... java -cp ... dev.fastframe.migrations.MigrationCli <directory> */
public final class MigrationCli {
    private MigrationCli() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: MigrationCli <directory>");
        String url = System.getenv("FF_DB_URL");
        if (url == null || url.isBlank()) throw new IllegalStateException("FF_DB_URL is required");
        var db = new Database(new DriverManagerDataSource(url, System.getenv("FF_DB_USER"), System.getenv("FF_DB_PASSWORD")));
        var result = new MigrationRunner(db).migrate(SqlMigrations.load(Path.of(args[0])));
        System.out.printf("Migrations applied: %d, skipped: %d%n", result.applied(), result.skipped());
    }
}
