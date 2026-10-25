package dev.fastframe.cache;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class CacheTest {
    private final AtomicLong ticker = new AtomicLong(1000L);
    private final InMemoryCacheStore memory = new InMemoryCacheStore(3, ticker::get);
    private final Cache cache = new Cache(memory);

    @Test void getPutTtlAndEmptyValues() {
        assertTrue(cache.get("missing").isEmpty());
        cache.put("a", "", Duration.ofNanos(10));
        assertEquals(Optional.of(""), cache.get("a"));
        ticker.addAndGet(9);
        assertEquals("", cache.get("a").orElseThrow());
        ticker.incrementAndGet();
        assertFalse(cache.has("a"));
        assertEquals(0, memory.size());
    }

    @Test void addIsAtomicAndExpiredCanBeReplaced() {
        assertTrue(cache.add("k", "one", Duration.ofNanos(3)));
        assertFalse(cache.add("k", "two", Duration.ofNanos(3)));
        ticker.addAndGet(3);
        assertTrue(cache.add("k", "two", Duration.ofNanos(3)));
        assertEquals("two", cache.get("k").orElseThrow());
    }

    @Test void evictionAndForeverStillRespectCapacity() {
        cache.forever("a", "1"); cache.forever("b", "2");
        cache.get("a"); // LRU makes b oldest
        cache.forever("c", "3"); cache.forever("d", "4");
        assertTrue(cache.get("b").isEmpty());
        assertEquals(3, memory.size());
        assertTrue(cache.forget("a"));
        assertFalse(cache.forget("a"));
    }

    @Test void rememberMemoizesUntilExpiryAndFailuresDoNotCache() {
        AtomicInteger loads = new AtomicInteger();
        assertEquals("v1", cache.remember("r", Duration.ofNanos(2), () -> "v" + loads.incrementAndGet()));
        assertEquals("v1", cache.remember("r", Duration.ofNanos(2), () -> "unused"));
        ticker.addAndGet(2);
        assertEquals("v2", cache.remember("r", Duration.ofNanos(2), () -> "v" + loads.incrementAndGet()));
        assertThrows(IllegalStateException.class, () -> cache.remember("fail", Duration.ofSeconds(1), () -> { throw new IllegalStateException("failure"); }));
        assertTrue(cache.get("fail").isEmpty());
        assertEquals("forever", cache.rememberForever("f", () -> "forever"));
    }

    @Test void validatesInputsAndRejectsOverflow() {
        assertThrows(IllegalArgumentException.class, () -> cache.put("a","v",Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> cache.put("a","v",Duration.ofDays(200000)));
        assertThrows(IllegalArgumentException.class, () -> cache.get(" "));
        assertThrows(NullPointerException.class, () -> cache.forever("x",null));
        assertEquals(1, CacheStore.ttlMillisRoundedUp(Duration.ofNanos(1)));
        assertEquals(2, CacheStore.ttlMillisRoundedUp(Duration.ofNanos(1_000_001)));
    }

    @Test void concurrentRememberSingleLoadWithinOneFacade() throws Exception {
        AtomicInteger count = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            var calls = IntStream.range(0, 24)
                    .mapToObj(i -> workers.submit(() -> cache.remember("shared", Duration.ofMinutes(1), () -> {
                        count.incrementAndGet();
                        try { Thread.sleep(5); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                        return "only-once";
                    }))).toList();
            for (var call : calls) assertEquals("only-once", call.get(5, TimeUnit.SECONDS));
            assertEquals(1, count.get());
        } finally { workers.shutdownNow(); }
    }

    @Test void rememberAsyncUsesSuppliedExecutor() throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            assertEquals("async", cache.rememberAsync("async",Duration.ofSeconds(2), () -> "async", worker)
                    .toCompletableFuture().get(3,TimeUnit.SECONDS));
        }
    }
}
