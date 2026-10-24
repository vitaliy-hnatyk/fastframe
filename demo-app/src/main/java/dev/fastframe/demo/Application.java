package dev.fastframe.demo;

import dev.fastframe.core.http.Router;
import dev.fastframe.demo.routes.ApiRoutes;
import dev.fastframe.netty.NettyServer;
import dev.fastframe.netty.NettyOptions;
import java.time.Duration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class Application {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        // Bounded pool for demo tasks that could block; NEVER use CallerRunsPolicy on Netty loops.
        ThreadPoolExecutor blockingExecutor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.AbortPolicy());
        Router router = new Router();
        ApiRoutes.register(router, blockingExecutor);

        NettyOptions options = new NettyOptions(
                Integer.parseInt(System.getenv().getOrDefault("MAX_IN_FLIGHT", "256")),
                Integer.parseInt(System.getenv().getOrDefault("MAX_PIPELINED", "64")),
                Integer.parseInt(System.getenv().getOrDefault("MAX_REQUEST_BYTES", "65536")),
                Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("REQUEST_TIMEOUT_MS", "10000"))));
        try (NettyServer server = new NettyServer(router, "0.0.0.0", port, options)) {
            server.start();
            System.out.println("FastFrame demo listening on http://localhost:" + server.boundPort());
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.awaitClose();
        } finally {
            blockingExecutor.shutdown();
        }
    }
}
