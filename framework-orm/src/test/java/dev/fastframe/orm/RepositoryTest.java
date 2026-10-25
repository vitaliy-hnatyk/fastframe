package dev.fastframe.orm;

import dev.fastframe.database.Database;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryTest {
    record Person(Long id, String name) {}
    static class Mapper implements EntityMapper<Person, Long> {
        public String table() { return "people"; }
        public String idColumn() { return "id"; }
        public Long id(Person person) { return person.id(); }
        public Person fromRow(Map<String, Object> row) {
            return new Person(((Number)row.get("id")).longValue(), (String)row.get("name"));
        }
        public Map<String, Object> toColumns(Person person) {
            var result = new LinkedHashMap<String,Object>();
            result.put("id", person.id()); result.put("name", person.name()); return result;
        }
    }
    Database db;
    Repository<Person, Long> people;
    @BeforeEach void before() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:orm" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        db = new Database(ds);
        db.execute("CREATE TABLE \"people\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100))");
        people = new Repository<>(db, new Mapper());
    }
    @Test void typedCrudAndNotFound() {
        assertEquals(1, people.insert(new Person(10L, "Ada")));
        assertEquals("Ada", people.findOrFail(10L).name());
        assertEquals(1, people.update(new Person(10L, "Grace")));
        assertEquals(List.of(new Person(10L, "Grace")), people.get(10));
        assertEquals(1L, people.count());
        assertEquals(1, people.save(new Person(10L, "Ada")));
        assertEquals(1, people.save(new Person(11L, "Alan")));
        assertEquals(1, people.where("name", "Alan", 10).size());
        assertEquals(1, people.delete(10L));
        assertThrows(EntityNotFoundException.class, () -> people.findOrFail(10L));
        assertThrows(IllegalArgumentException.class, () -> people.insert(new Person(null, "Bad")));
        assertThrows(IllegalArgumentException.class, () -> people.get(0));
        assertThrows(IllegalArgumentException.class, () -> people.where("id;DROP", 1, 10));
    }
    @Test void scopedRepositoryParticipatesInTransaction() {
        assertThrows(IllegalArgumentException.class, () -> db.transaction(tx -> {
            new Repository<>(tx, new Mapper()).insert(new Person(1L, "Rollback"));
            throw new IllegalArgumentException("reject");
        }));
        assertTrue(people.find(1L).isEmpty());
    }
}
