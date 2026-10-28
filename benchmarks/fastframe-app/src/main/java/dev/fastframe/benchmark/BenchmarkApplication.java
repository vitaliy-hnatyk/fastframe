package dev.fastframe.benchmark;

import dev.fastframe.core.http.Router;
import dev.fastframe.generated.FastFrameRoutes;
import dev.fastframe.netty.NettyOptions;
import dev.fastframe.netty.NettyServer;
import dev.fastframe.netty.TlsSettings;
import dev.fastframe.netty.NettyTransport;
import dev.fastframe.core.http.Response;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import java.time.Duration;

/** Minimal benchmark-only process; no demo middleware or background tasks. */
public final class BenchmarkApplication {
    private BenchmarkApplication() { }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "18080"));
        Router router = new Router();
        FastFrameRoutes.register(router);
        router.get("/bench/events", request -> Response.sse(sink -> {sink.send("ready", "fastframe");sink.close();}));
        router.get("/bench/ws", request -> Response.websocket(text -> CompletableFuture.completedFuture(text)));
        int maxConcurrent = Integer.parseInt(System.getenv().getOrDefault("MAX_IN_FLIGHT", "256"));
        NettyOptions options = new NettyOptions(maxConcurrent, 64, 65_536, Duration.ofSeconds(10));
        String cert=System.getenv("BENCH_TLS_CERT");
        String key=System.getenv("BENCH_TLS_KEY");
        if((cert==null)!=(key==null))throw new IllegalArgumentException("BENCH_TLS_CERT and BENCH_TLS_KEY must both be set");
        TlsSettings tls=cert==null?null:new TlsSettings(Path.of(cert),Path.of(key),Boolean.parseBoolean(System.getenv().getOrDefault("BENCH_HTTP2","false")));
        NettyTransport transport=Boolean.parseBoolean(System.getenv().getOrDefault("BENCH_H2C","false"))?
           NettyTransport.h2c():tls==null?NettyTransport.http11():NettyTransport.https(tls);
        try (NettyServer server = new NettyServer(router, "127.0.0.1", port, options, transport)) {
            server.start();
            System.out.println("FastFrame benchmark listening on " + server.boundPort());
            server.awaitClose();
        }
    }
}
