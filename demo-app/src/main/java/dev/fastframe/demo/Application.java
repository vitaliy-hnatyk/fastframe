package dev.fastframe.demo;

import dev.fastframe.application.AppContext;
import dev.fastframe.application.FastFrameApp;
import dev.fastframe.config.Config;
import dev.fastframe.netty.NettyOptions;
import dev.fastframe.netty.NettyServer;
import dev.fastframe.observability.HttpMetrics;
import dev.fastframe.observability.Readiness;
import dev.fastframe.observability.OperationsRoutes;
import dev.fastframe.security.*;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.time.Duration;

/** Minimal application entry point; modules are installed by explicit providers. */
public final class Application {
    private Application() { }

    public static void main(String[] args) throws Exception {
        Config config = Config.load();
        int port = config.intValue("PORT", 8080, 1, 65535);
        try (AppContext app = FastFrameApp.boot(config,
                new DemoLifecycleProvider(),
                new DemoProviders.Infrastructure(),
                new DemoProviders.HttpRoutes(),
                new DemoProviders.CacheRoutesProvider(),
                new DemoProviders.LocalQueue(),
                new DemoProviders.DatabaseRoutesProvider(),
                new DemoProviders.StorageRoutesProvider())) {

            NettyOptions options = new NettyOptions(
                    config.intValue("MAX_IN_FLIGHT", 256, 1, 100_000),
                    config.intValue("MAX_PIPELINED", 64, 1, 4096),
                    config.intValue("MAX_REQUEST_BYTES", 65536, 1, 10_000_000),
                    config.millis("REQUEST_TIMEOUT_MS", 10000, 1, 3_600_000));

            String host = config.get("FF_HOST", "127.0.0.1");
            boolean metricsEnabled = config.booleanValue("FF_ENABLE_METRICS", false);
            if (metricsEnabled && !(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"))) {
                throw new IllegalArgumentException("Metrics endpoint may only bind to localhost in v0.19");
            }
            var metrics = new HttpMetrics();
            var readiness = new Readiness();
            OperationsRoutes.register(app.router(), readiness, metrics, metricsEnabled);
            // Exact origin allowlist; no wildcard, no implicit trust of X-Forwarded-For.
            String allowed = config.get("FF_CORS_ALLOWED_ORIGINS", "").trim();
            Set<String> origins = allowed.isEmpty() ? Set.of() : Arrays.stream(allowed.split(","))
                    .map(String::trim).collect(Collectors.toUnmodifiableSet());
            CorsPolicy cors = new CorsPolicy(origins,
                    Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"),
                    Set.of("authorization", "content-type", "x-request-id"),
                    config.booleanValue("FF_CORS_ALLOW_CREDENTIALS", false), 600);
            int perMinute = config.intValue("FF_RATE_LIMIT_PER_MINUTE", 0, 0, 100_000);
            IpRateLimiter limiter = perMinute == 0 ? null : new IpRateLimiter(perMinute,
                    Duration.ofMinutes(1), config.intValue("FF_RATE_LIMIT_MAX_IPS", 4096, 1, 100_000));
            HttpSecurity security = new HttpSecurity(cors, limiter, true,
                    config.booleanValue("FF_HSTS", false));
            try (NettyServer server = new NettyServer(app.router(), host, port, options,
                    metrics, readiness, config.booleanValue("FF_ACCESS_LOG", false), security)) {
                server.start();
                app.events().publish(new DemoLifecycleProvider.Started(server.boundPort()));
                System.out.println("FastFrame demo listening on http://localhost:" + server.boundPort());
                // Stops accepting traffic, drains pending responses, then closes resources.
                Runtime.getRuntime().addShutdownHook(new Thread(
                        () -> server.shutdownGracefully(Duration.ofSeconds(5)), "fastframe-shutdown"));
                server.awaitClose();
            }
        }
    }
}
