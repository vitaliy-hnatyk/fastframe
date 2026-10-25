package dev.fastframe.application;

import dev.fastframe.config.Config;
import dev.fastframe.core.http.Router;
import dev.fastframe.events.EventBus;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Per-application typed registry, HTTP router, and deterministic shutdown hooks. */
public final class AppContext implements AutoCloseable {
    enum Phase { REGISTER, BOOT, RUNNING, CLOSED }
    private final Config config;
    private final Router router = new Router();
    private final EventBus events = new EventBus();
    private final Map<Class<?>, Object> services = new HashMap<>();
    private final Deque<AutoCloseable> shutdown = new ArrayDeque<>();
    private Phase phase = Phase.REGISTER;

    AppContext(Config config) { this.config = Objects.requireNonNull(config); }

    public Config config() { checkOpen(); return config; }
    public Router router() { checkOpen(); return router; }
    public EventBus events() { checkOpen(); return events; }

    /** Only valid during registration; duplicate bindings are rejected. */
    public <T> void bind(Class<T> type, T service) {
        if (phase != Phase.REGISTER) throw new IllegalStateException("Services may only be bound during register()");
        Objects.requireNonNull(type);
        Objects.requireNonNull(service);
        if (!type.isInstance(service)) throw new IllegalArgumentException("Invalid binding: " + type.getName());
        if (services.putIfAbsent(type, service) != null)
            throw new IllegalStateException("Duplicate binding: " + type.getName());
    }

    public <T> T require(Class<T> type) {
        checkOpen();
        Objects.requireNonNull(type);
        Object result = services.get(type);
        if (result == null) throw new IllegalStateException("Missing binding: " + type.getName());
        return type.cast(result);
    }

    /** Register close actions for ownership transferred to the application. Reverse-order shutdown. */
    public void onShutdown(AutoCloseable resource) {
        if (phase == Phase.CLOSED) throw new IllegalStateException("Application already closed");
        shutdown.push(Objects.requireNonNull(resource));
    }

    void booting() { phase = Phase.BOOT; }
    void running() { phase = Phase.RUNNING; }
    private void checkOpen() {
        if (phase == Phase.CLOSED) throw new IllegalStateException("Application already closed");
    }

    @Override public void close() throws Exception {
        if (phase == Phase.CLOSED) return;
        phase = Phase.CLOSED;
        Exception failure = null;
        while (!shutdown.isEmpty()) {
            try { shutdown.pop().close(); }
            catch (Exception ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        services.clear();
        if (failure != null) throw failure;
    }
}
