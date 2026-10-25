package dev.fastframe.events;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EventBusTest {
    record Created(long id) { }
    record Updated(long id) { }

    @Test void orderExactTypeUnsubscribe() throws Exception {
        var events = new EventBus();
        var calls = new StringBuilder();
        AutoCloseable first = events.on(Created.class, e -> calls.append("A" + e.id()));
        events.on(Created.class, e -> calls.append("B" + e.id()));
        events.publish(new Created(5));
        events.publish(new Updated(5));
        assertEquals("A5B5", calls.toString());
        first.close(); first.close();
        events.publish(new Created(2));
        assertEquals("A5B5B2", calls.toString());
    }

    @Test void asyncPropagatesListenerFailure() {
        var events = new EventBus();
        events.on(Created.class, e -> { throw new IllegalArgumentException("bad"); });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = events.publishAsync(new Created(1), executor).toCompletableFuture();
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test void asyncFinishesOnExecutor() throws Exception {
        var events = new EventBus();
        var counter = new AtomicInteger();
        events.on(Created.class, e -> counter.incrementAndGet());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new CompletableFuture<?>[40];
            for (int i=0;i<futures.length;i++) futures[i] = events.publishAsync(new Created(i), executor).toCompletableFuture();
            CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
            assertEquals(40, counter.get());
        } finally { executor.shutdownNow(); }
    }
}
