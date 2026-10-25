package dev.fastframe.observability;

import java.util.concurrent.atomic.AtomicBoolean;

/** Readiness is independent of liveness; a draining process stays live but is not ready. */
public final class Readiness {
    private final AtomicBoolean ready = new AtomicBoolean(false);
    public void ready() { ready.set(true); }
    public void notReady() { ready.set(false); }
    public boolean isReady() { return ready.get(); }
}
