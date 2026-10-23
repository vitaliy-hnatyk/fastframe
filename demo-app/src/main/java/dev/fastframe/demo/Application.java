package dev.fastframe.demo;

import dev.fastframe.core.http.Router;
import dev.fastframe.demo.routes.ApiRoutes;
import dev.fastframe.netty.NettyServer;

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

        try (NettyServer server = new NettyServer(router, "0.0.0.0", port)) {
            server.start();
            System.out.println("FastFrame demo listening on http://localhost:" + server.boundPort());
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.awaitClose();
        } finally {
            blockingExecutor.shutdown();
        }
    }
}
