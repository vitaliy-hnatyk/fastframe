package dev.fastframe.demo;

import dev.fastframe.application.AppContext;
import dev.fastframe.application.ServiceProvider;
import java.util.concurrent.atomic.AtomicInteger;

/** Demonstrates service binding, event subscription, and clean shutdown. */
public final class DemoLifecycleProvider implements ServiceProvider {
    public record Started(int port) { }
    public static final class StartupCounter { private final AtomicInteger count = new AtomicInteger();
        public int count() { return count.get(); }
        private void increment() { count.incrementAndGet(); }
    }

    @Override public void register(AppContext context) {
        context.bind(StartupCounter.class, new StartupCounter());
    }

    @Override public void boot(AppContext context) {
        var counter = context.require(StartupCounter.class);
        context.onShutdown(context.events().on(Started.class, event -> counter.increment()));
    }
}
