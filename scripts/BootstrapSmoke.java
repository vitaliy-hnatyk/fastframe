import dev.fastframe.config.Config;
import dev.fastframe.events.EventBus;
import dev.fastframe.application.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class BootstrapSmoke {
    record Ping(int id) { }
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var config = Config.load(Path.of("__does_not_exist__"), Map.of("MODE", "test"));
        var stages = new ArrayList<String>();
        ServiceProvider first = new ServiceProvider() {
            public void register(AppContext c) { stages.add("registerA"); c.bind(String.class,"ok"); c.onShutdown(() -> stages.add("closeA")); }
            public void boot(AppContext c) { stages.add("bootA"); check(c.require(Integer.class) == 42); }
        };
        ServiceProvider second = new ServiceProvider() {
            public void register(AppContext c) { stages.add("registerB"); c.bind(Integer.class,42); c.onShutdown(() -> stages.add("closeB")); }
            public void boot(AppContext c) { stages.add("bootB"); check(c.config().require("MODE").equals("test")); }
        };
        AppContext context = FastFrameApp.boot(config, first, second);
        check(stages.equals(List.of("registerA","registerB","bootA","bootB")));
        AtomicInteger hits = new AtomicInteger();
        AutoCloseable sub = context.events().on(Ping.class, e -> hits.addAndGet(e.id()));
        context.events().publish(new Ping(2));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try { context.events().publishAsync(new Ping(3), executor).toCompletableFuture().get(5,TimeUnit.SECONDS); }
        finally { executor.shutdownNow(); }
        check(hits.get()==5);
        sub.close(); sub.close(); context.events().publish(new Ping(50)); check(hits.get()==5);
        boolean duplicate=false;
        try {context.bind(String.class,"again");} catch(IllegalStateException ok) {duplicate=true;}
        check(duplicate);
        context.close(); context.close();
        check(stages.equals(List.of("registerA","registerB","bootA","bootB","closeB","closeA")));
        checkFailureCleanup(config);
        System.out.println("FastFrame v0.17 application/event smoke: PASS");
    }
    static void checkFailureCleanup(Config config) throws Exception {
        var clean = new AtomicInteger();
        try {
            FastFrameApp.boot(config, new ServiceProvider() {
                public void register(AppContext c) {c.onShutdown(clean::incrementAndGet);}
                public void boot(AppContext c) {throw new IllegalStateException("intentional");}
            });
            throw new AssertionError("Expected boot failure");
        } catch(IllegalStateException expected) {check(clean.get()==1);}
    }
}
