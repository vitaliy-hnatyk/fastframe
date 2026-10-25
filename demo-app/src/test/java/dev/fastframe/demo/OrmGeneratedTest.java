package dev.fastframe.demo;

import dev.fastframe.demo.models.User;
import dev.fastframe.demo.models.UserFastFrameMapper;
import dev.fastframe.database.Database;
import dev.fastframe.migrations.MigrationRunner;
import dev.fastframe.migrations.SqlMigration;
import dev.fastframe.orm.Repository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrmGeneratedTest {
    @Test void mapperIsGeneratedAndRepositoryWorks() {
        UserFastFrameMapper mapper = new UserFastFrameMapper();
        assertEquals("users", mapper.table());
        assertEquals("id", mapper.idColumn());
        assertEquals(new User(3L,"Ada"), mapper.fromRow(Map.of("id", Integer.valueOf(3), "name", "Ada")));
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:demoorm"+System.nanoTime()+";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        Database db = new Database(ds);
        var migration = SqlMigration.of(1,"create_users",
                "CREATE TABLE \"users\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100))");
        assertEquals(1, new MigrationRunner(db).migrate(List.of(migration)).applied());
        Repository<User,Long> repo = new Repository<>(db, mapper);
        assertEquals(1, repo.insert(new User(7L,"Grace")));
        assertEquals(new User(7L,"Grace"), repo.findOrFail(7L));
        assertEquals(1, repo.update(new User(7L,"Lina")));
        assertEquals("Lina", repo.findOrFail(7L).name());
        assertEquals(1, repo.delete(7L));
    }
}
