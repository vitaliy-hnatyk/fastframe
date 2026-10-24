package dev.fastframe.benchmark;

import dev.fastframe.core.http.Router;
import dev.fastframe.generated.FastFrameRoutes;
import dev.fastframe.netty.NettyOptions;
import dev.fastframe.netty.NettyServer;

import java.time.Duration;

/** Minimal benchmark-only process; no demo middleware or background tasks. */
public final class BenchmarkApplication {
    private BenchmarkApplication() { }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "18080"));
        Router router = new Router();
        FastFrameRoutes.register(router);
        int maxConcurrent = Integer.parseInt(System.getenv().getOrDefault("MAX_IN_FLIGHT", "256"));
        NettyOptions options = new NettyOptions(maxConcurrent, 64, 65_536, Duration.ofSeconds(10));
        try (NettyServer server = new NettyServer(router, "127.0.0.1", port, options)) {
            server.start();
            System.out.println("FastFrame benchmark listening on " + server.boundPort());
            server.awaitClose();
        }
    }
}
