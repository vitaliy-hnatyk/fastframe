package dev.fastframe.netty;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.observability.HttpMetrics;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import dev.fastframe.core.http.Request;
import static org.junit.jupiter.api.Assertions.*;

final class NettyObservabilityTest {
    @Test void requestIdAndMetricsAreProducedWithoutTrustingClientHeader() {
        var m = new HttpMetrics();
        AtomicReference<Request> captured = new AtomicReference<>();
        Router r = new Router().get("/hi", req -> { captured.set(req); return Response.text("ok"); });
        EmbeddedChannel ch = new EmbeddedChannel(new NettyRequestHandler(r,
                NettyOptions.defaults(), new Semaphore(256), m, false));
        try {
            var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hi");
            req.headers().set("X-Request-ID", "attacker-id");
            ch.writeInbound(req);
            FullHttpResponse out = ch.readOutbound();
            assertNotNull(out);
            try {
                String id = out.headers().get("X-Request-ID");
                assertNotNull(id);
                assertNotEquals("attacker-id", id);
                assertEquals(id, captured.get().requestId());
                assertEquals(0, m.activeRequests());
                assertEquals(1, m.completedRequests());
            } finally { ReferenceCountUtil.release(out); }
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test void drainingWaitsForPendingAsyncResponseThenClosesConnection() {
        var future = new CompletableFuture<Response>();
        var m = new HttpMetrics();
        Router r = new Router().getAsync("/slow", req -> future);
        EmbeddedChannel ch = new EmbeddedChannel(new NettyRequestHandler(r,
                new NettyOptions(2, 8, 1024, Duration.ofSeconds(5)), new Semaphore(2), m, false));
        try {
            ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/slow"));
            assertEquals(1, m.activeRequests());
            ch.pipeline().get(NettyRequestHandler.class).beginDrain(ch);
            assertTrue(ch.isOpen());
            future.complete(Response.text("done"));
            ch.runPendingTasks();
            FullHttpResponse response = ch.readOutbound();
            assertNotNull(response);
            try {
                assertEquals(200, response.status().code());
                assertEquals("close", response.headers().get("Connection"));
            } finally { ReferenceCountUtil.release(response); }
            ch.runPendingTasks();
            assertEquals(0, m.activeRequests());
            assertFalse(ch.isOpen());
        } finally { ch.finishAndReleaseAll(); }
    }
}
