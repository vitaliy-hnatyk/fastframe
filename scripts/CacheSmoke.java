import dev.fastframe.cache.Cache;
import dev.fastframe.cache.InMemoryCacheStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class CacheSmoke {
    public static void main(String[] args) {
        var cache = new Cache(new InMemoryCacheStore(2));
        AtomicInteger loads = new AtomicInteger();
        String one = cache.remember("key", Duration.ofSeconds(5), () -> "v" + loads.incrementAndGet());
        String two = cache.remember("key", Duration.ofSeconds(5), () -> "v" + loads.incrementAndGet());
        if (!"v1".equals(one) || !one.equals(two) || loads.get() != 1) throw new AssertionError("remember failed");
        if (!cache.add("fresh", "hello", Duration.ofSeconds(5))) throw new AssertionError("add failed");
        if (cache.add("fresh", "other", Duration.ofSeconds(5))) throw new AssertionError("non-atomic add");
        if (!cache.forget("key") || cache.has("key")) throw new AssertionError("forget failed");
        System.out.println("FastFrame v0.12 cache smoke PASS");
    }
}
