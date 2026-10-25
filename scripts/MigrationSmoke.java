import dev.fastframe.migrations.SqlMigrations;
import java.nio.file.Path;
public class MigrationSmoke {
    public static void main(String[] args) throws Exception {
        var migrations = SqlMigrations.load(Path.of("demo-app/migrations"));
        if (migrations.size() != 1 || migrations.getFirst().version() != 1 ||
            migrations.getFirst().checksum().length() != 64) throw new AssertionError();
        System.out.println("MIGRATION LOAD SMOKE PASS");
    }
}
