package dev.fastframe.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

final class ConfigTest {
    @TempDir Path dir;

    @Test void loadsEnvWithOverrideAndQuotedValues() throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "# comment\nPORT=8080\nFF_DB_PASSWORD='a # b'\nFOO=hello # comment\nTXT=\"hello\\nworld\"\n");
        Config config = Config.load(file, Map.of("PORT", "9090"));
        assertEquals(9090, config.intValue("PORT", 80, 1, 65535));
        assertEquals("a # b", config.get("FF_DB_PASSWORD"));
        assertEquals("hello", config.get("FOO"));
        assertEquals("hello\nworld", config.get("TXT"));
    }

    @Test void rejectsDuplicateInvalidAndMalformedValues() throws Exception {
        Path file = dir.resolve(".env");
        Files.writeString(file, "A=1\nA=2\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(file, Map.of()));
        Files.writeString(file, "BAD=\"unfinished\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(file, Map.of()));
        Files.writeString(file, "BAD NAME=2\n");
        assertThrows(IllegalArgumentException.class, () -> Config.load(file, Map.of()));
    }

    @Test void defaultsTypesAndMissingFile() throws Exception {
        Config config = Config.load(dir.resolve("missing.env"), Map.of("FLAG", "off", "TIMEOUT", "200"));
        assertFalse(config.booleanValue("FLAG", true));
        assertEquals(200L, config.millis("TIMEOUT", 10, 1, 500).toMillis());
        assertEquals(5, config.intValue("UNSET", 5, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> config.require("MISSING_SECRET"));
        assertThrows(IllegalArgumentException.class, () -> config.intValue("TIMEOUT", 1, 1, 100));
    }
}
