package dev.fastframe.core.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Cooperative request cancellation. Callbacks should be short and non-blocking. */
public final class CancellationToken {
    private boolean cancelled;
    private final List<Runnable> callbacks = new ArrayList<>();

    public synchronized boolean isCancelled() { return cancelled; }

    /** Registers a callback; immediately invokes it if cancellation has already occurred. */
    public void onCancel(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        synchronized (this) {
            if (!cancelled) {
                callbacks.add(callback);
                return;
            }
        }
        safelyRun(callback);
    }

    /** Idempotent; does not interrupt threads or forcibly cancel a CompletionStage. */
    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            toRun = List.copyOf(callbacks);
            callbacks.clear();
        }
        for (Runnable callback : toRun) safelyRun(callback);
    }

    private static void safelyRun(Runnable callback) {
        try { callback.run(); }
        catch (Throwable ignored) { /* One callback cannot prevent later cancellation callbacks. */ }
    }
}
