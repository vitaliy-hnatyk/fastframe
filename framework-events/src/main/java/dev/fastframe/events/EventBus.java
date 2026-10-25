package dev.fastframe.events;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Lightweight exact-type event dispatcher; no reflection or ambient global state. */
public final class EventBus {
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<Object>>> listeners =
            new ConcurrentHashMap<>();

    /** Thread-safe registration. Closing the returned subscription removes the listener. */
    public <T> AutoCloseable on(Class<T> type, Consumer<? super T> listener) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        Consumer<Object> dispatch = event -> listener.accept(type.cast(event));
        listeners.compute(type, (ignored, existing) -> {
            var handlers = existing == null ? new CopyOnWriteArrayList<Consumer<Object>>() : existing;
            handlers.add(dispatch);
            return handlers;
        });
        AtomicBoolean subscribed = new AtomicBoolean(true);
        return () -> {
            if (subscribed.compareAndSet(true, false)) {
                listeners.computeIfPresent(type, (ignored, handlers) -> {
                    handlers.remove(dispatch);
                    return handlers.isEmpty() ? null : handlers;
                });
            }
        };
    }

    /** Dispatches in registration order on the caller thread. Exceptions propagate. */
    public void publish(Object event) {
        Objects.requireNonNull(event, "event");
        var handlers = listeners.get(event.getClass());
        if (handlers == null) return;
        for (Consumer<Object> listener : handlers) listener.accept(event);
    }

    /** Dispatches on a caller-supplied executor; errors surface via the returned future. */
    public CompletionStage<Void> publishAsync(Object event, Executor executor) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(executor, "executor");
        return CompletableFuture.runAsync(() -> publish(event), executor);
    }
}
