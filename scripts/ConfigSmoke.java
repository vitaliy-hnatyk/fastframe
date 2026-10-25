import dev.fastframe.config.Config;
import java.nio.file.Files;
import java.util.Map;

/** Dependency-free Java 21 smoke coverage for Config without relying on Maven. */
public class ConfigSmoke {
    public static void main(String[] ignored) throws Exception {
        var env = Files.createTempFile("fastframe-config-smoke", ".env");
        try {
            Files.writeString(env, "PORT=8080\nFF_DB_URL='jdbc:h2:mem:fastframe;DB_CLOSE_DELAY=-1'\nDEBUG=false # trailing comment\n");
            Config config = Config.load(env, Map.of("PORT", "9091"));
            check(config.intValue("PORT", 8080, 1, 65535) == 9091);
            check(config.get("FF_DB_URL").contains("DB_CLOSE_DELAY=-1"));
            check(!config.booleanValue("DEBUG", true));
            check(config.millis("TIMEOUT", 1000, 1, 60000).toMillis() == 1000);
            Files.writeString(env, "DUP=1\nDUP=2\n");
            boolean duplicateRejected = false;
            try { Config.load(env, Map.of()); }
            catch (IllegalArgumentException expected) { duplicateRejected = true; }
            check(duplicateRejected);
            System.out.println("CONFIG SMOKE PASS: env overrides, types, comments, duplicates");
        } finally {
            Files.deleteIfExists(env);
        }
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Config smoke failure"); }
}
