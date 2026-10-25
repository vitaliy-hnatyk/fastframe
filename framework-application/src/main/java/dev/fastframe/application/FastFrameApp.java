package dev.fastframe.application;

import dev.fastframe.config.Config;
import java.util.List;
import java.util.Objects;

/** Explicit application bootstrap: register all providers, then boot all providers. */
public final class FastFrameApp {
    private FastFrameApp() { }

    public static AppContext boot(Config config, ServiceProvider... providers) throws Exception {
        return boot(config, List.of(providers));
    }

    public static AppContext boot(Config config, List<? extends ServiceProvider> providers) throws Exception {
        Objects.requireNonNull(providers, "providers");
        var copy = List.copyOf(providers);
        AppContext context = new AppContext(config);
        try {
            for (ServiceProvider provider : copy) provider.register(context);
            context.booting();
            for (ServiceProvider provider : copy) provider.boot(context);
            context.running();
            return context;
        } catch (Exception | Error original) {
            try { context.close(); }
            catch (Exception failure) { original.addSuppressed(failure); }
            throw original;
        }
    }
}
