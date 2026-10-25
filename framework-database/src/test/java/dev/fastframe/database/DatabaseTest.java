package dev.fastframe.database;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    private Database db;

    @BeforeEach void create() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dbtest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        db = new Database(ds);
        db.execute("CREATE TABLE \"users\" (\"id\" BIGINT PRIMARY KEY, \"name\" VARCHAR(100), \"active\" BOOLEAN, \"age\" INTEGER)");
        db.table("users").insert(Map.of("id", 1L, "name", "Ada", "active", true, "age", 30));
        db.table("users").insert(Map.of("id", 2L, "name", "Bob", "active", false, "age", 20));
        db.table("users").insert(Map.of("id", 3L, "name", "Alice", "active", true, "age", 27));
    }

    @Test void selectWhereSortLimitCountAndExists() {
        var users = db.table("users").select("name").where("active", true)
                .orderBy("id", Query.Direction.DESC).limit(1).get();
        assertEquals("Alice", users.get(0).get("name"));
        assertEquals(2L, db.table("users").where("active", true).count());
        assertTrue(db.table("users").where("id", 1L).exists());
        assertFalse(db.table("users").where("id", 100L).exists());
        assertTrue(db.table("users").where("id", 100L).first().isEmpty());
        assertEquals("Bob", db.table("users").whereIn("id", List.of(2L)).first().orElseThrow().get("name"));
        assertTrue(db.table("users").whereIn("id", List.of()).get().isEmpty());
        assertEquals(1, db.table("users").orderBy("id", Query.Direction.ASC).limit(1).offset(1).get().size());
    }

    @Test void updatesAndDeletesAreParameterizedAndRequireAFilter() {
        assertThrows(IllegalStateException.class, () -> db.table("users").update(Map.of("name", "oops")));
        assertThrows(IllegalStateException.class, () -> db.table("users").delete());
        assertEquals(1, db.table("users").where("id", 1L).update(Map.of("name", "A; DROP TABLE users")));
        assertEquals("A; DROP TABLE users", db.table("users").where("id", 1L).first().orElseThrow().get("name"));
        assertEquals(1, db.table("users").where("id", 2L).delete());
        assertEquals(2, db.table("users").get().size());
    }

    @Test void rejectsIdentifierInjectionButUsesBindParametersForValues() {
        assertThrows(IllegalArgumentException.class, () -> db.table("users; DROP TABLE users"));
        assertThrows(IllegalArgumentException.class, () -> db.table("users").select("id; DROP"));
        assertThrows(IllegalArgumentException.class, () -> db.table("users").orderBy("name DESC", Query.Direction.ASC));
        var q = db.table("users").where("name", "' OR 1=1 --").limit(5);
        assertEquals("SELECT * FROM \"users\" WHERE \"name\" = ? LIMIT ?", q.toSql());
        assertEquals(List.of("' OR 1=1 --", 5), q.bindings());
        assertTrue(q.get().isEmpty());
    }

    @Test void transactionsCommitRollbackAndRejectEscapedScope() {
        assertEquals("done", db.transaction(tx -> {
            tx.table("users").where("id", 1L).update(Map.of("name", "One"));
            tx.table("users").insert(Map.of("id", 4L, "name", "Four", "active", false, "age", 40));
            assertEquals(4L, tx.table("users").count());
            return "done";
        }));
        assertEquals("One", db.table("users").where("id", 1L).first().orElseThrow().get("name"));
        assertThrows(IllegalStateException.class, () -> db.transaction(tx -> {
            tx.table("users").where("id", 1L).delete();
            throw new IllegalStateException("cancel");
        }));
        assertTrue(db.table("users").where("id", 1L).exists());
        var scope = new Transaction[1];
        db.transaction(tx -> { scope[0] = tx; return null; });
        assertThrows(IllegalStateException.class, () -> scope[0].table("users"));
    }

    @Test void whereNullAndInsertionWithNull() {
        Map<String,Object> record = new LinkedHashMap<>();
        record.put("id", 9L); record.put("name", null); record.put("active", false); record.put("age", 1);
        assertEquals(1, db.table("users").insert(record));
        assertEquals(1L, db.table("users").whereNull("name").count());
    }
}
