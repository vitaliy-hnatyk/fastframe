package dev.fastframe.application;

import dev.fastframe.config.Config;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapTest {
    private Config config() throws Exception { return Config.load(Path.of("__absent__"), Map.of()); }

    @Test void allRegisterBeforeBootAndReverseClose() throws Exception {
        var phases = new ArrayList<String>();
        ServiceProvider one = new ServiceProvider() {
            public void register(AppContext ctx) {
                phases.add("register-one"); ctx.bind(String.class, "a");
                ctx.onShutdown(() -> phases.add("close-one"));
            }
            public void boot(AppContext ctx) {
                phases.add("boot-one"); assertEquals(Integer.valueOf(12), ctx.require(Integer.class));
            }
        };
        ServiceProvider two = new ServiceProvider() {
            public void register(AppContext ctx) {
                phases.add("register-two"); ctx.bind(Integer.class,12);
                ctx.onShutdown(() -> phases.add("close-two"));
            }
            public void boot(AppContext ctx) { phases.add("boot-two"); }
        };
        try (AppContext app = FastFrameApp.boot(config(), one, two)) {
            assertSame(app.router(), app.router());
            assertEquals(List.of("register-one", "register-two", "boot-one", "boot-two"), phases);
            assertThrows(IllegalStateException.class, () -> app.bind(Integer.class,3));
        }
        assertEquals(List.of("register-one", "register-two", "boot-one", "boot-two", "close-two", "close-one"), phases);
    }

    @Test void failedBootClosesResourcesAndPreservesOriginal() throws Exception {
        var events = new ArrayList<String>();
        ServiceProvider broken = new ServiceProvider() {
            public void register(AppContext ctx) { ctx.onShutdown(() -> { events.add("closed"); throw new Exception("cleanup"); }); }
            public void boot(AppContext ctx) { throw new IllegalStateException("original"); }
        };
        var ex = assertThrows(IllegalStateException.class, () -> FastFrameApp.boot(config(), broken));
        assertEquals("original", ex.getMessage());
        assertEquals(1, ex.getSuppressed().length);
        assertEquals(List.of("closed"), events);
    }

    @Test void duplicateBindingRejected() throws Exception {
        ServiceProvider dup = ctx -> {
            ctx.bind(String.class, "first");
            assertThrows(IllegalStateException.class, () -> ctx.bind(String.class, "second"));
            assertEquals("first", ctx.require(String.class));
        };
        try (var app = FastFrameApp.boot(config(), dup)) { assertEquals("first", app.require(String.class)); }
    }
}
