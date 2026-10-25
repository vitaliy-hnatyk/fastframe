package dev.fastframe.migrations;

import dev.fastframe.database.Database;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MigrationRunnerTest {
    @TempDir Path dir;
    private Database database() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:migrate" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        return new Database(ds);
    }
    @Test void loadsAppliesSkipsAndDetectsChanges() throws Exception {
        Files.writeString(dir.resolve("V001__create_people.sql"),
                "CREATE TABLE \"people\" (\"id\" BIGINT PRIMARY KEY)");
        Files.writeString(dir.resolve("V002__add_name.sql"),
                "ALTER TABLE \"people\" ADD COLUMN \"name\" VARCHAR(80)");
        List<SqlMigration> list = SqlMigrations.load(dir);
        Database db = database();
        var runner = new MigrationRunner(db);
        assertEquals(new MigrationRunner.Result(2,0), runner.migrate(list));
        assertEquals(new MigrationRunner.Result(0,2), runner.migrate(list));
        assertEquals(2, db.table("fastframe_schema_migrations").get().size());
        assertThrows(IllegalStateException.class, () -> runner.migrate(List.of(list.get(0))));
        assertThrows(IllegalStateException.class, () -> runner.migrate(List.of(
                SqlMigration.of(1,"create_people", "CREATE TABLE changed (x INTEGER)"),list.get(1))));
    }
    @Test void rejectsDuplicateAndBadFileNames() throws Exception {
        Files.writeString(dir.resolve("bad.sql"), "SELECT 1");
        assertThrows(IllegalArgumentException.class, () -> SqlMigrations.load(dir));
        Files.delete(dir.resolve("bad.sql"));
        Files.writeString(dir.resolve("V001__initial.sql"), "CREATE TABLE x (id INT)");
        Files.writeString(dir.resolve("V1__again.sql"), "CREATE TABLE y (id INT)");
        assertThrows(IllegalArgumentException.class, () -> SqlMigrations.load(dir));
    }
}
