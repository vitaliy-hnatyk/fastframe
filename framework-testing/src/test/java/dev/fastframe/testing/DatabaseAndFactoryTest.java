package dev.fastframe.testing;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class DatabaseAndFactoryTest {
    @Test void fixtureCanRefreshAndIsolationIsReal() {
        String schema = "CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(80) NOT NULL)";
        try (var one = new H2DatabaseFixture(); var two = new H2DatabaseFixture()) {
            one.refresh(schema);
            two.refresh(schema);
            one.database().table("users").insert(Map.of("id", 7, "name", "Ada"));
            assertEquals(1, one.database().table("users").count());
            assertEquals(0, two.database().table("users").count());
            one.refresh(schema);
            assertEquals(0, one.database().table("users").count());
        }
    }
    @Test void factoryCreatesTypedValuesWithoutPersistence() {
        AtomicInteger n = new AtomicInteger();
        ModelFactory<String> factory = () -> "user-" + n.incrementAndGet();
        assertEquals(java.util.List.of("user-1", "user-2"), factory.makeMany(2));
        assertThrows(IllegalArgumentException.class, () -> factory.makeMany(-1));
        assertThrows(IllegalArgumentException.class, () -> factory.makeMany(10001));
    }
}
