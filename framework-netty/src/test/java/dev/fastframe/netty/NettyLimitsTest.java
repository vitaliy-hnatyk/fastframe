package dev.fastframe.netty;

import dev.fastframe.core.http.HttpException;
import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class NettyLimitsTest {
    private static DefaultFullHttpRequest request(String path) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    }

    @Test
    void globalConcurrencyLimitRejectsAndThenRecoversAcrossConnections() {
        CompletableFuture<Response> slow = new CompletableFuture<>();
        Router router = new Router().getAsync("/work", r -> slow);
        NettyOptions opts = new NettyOptions(1, 4, 1024, Duration.ofSeconds(3));
        Semaphore shared = new Semaphore(1);
        EmbeddedChannel first = new EmbeddedChannel(new NettyRequestHandler(router, opts, shared));
        EmbeddedChannel second = new EmbeddedChannel(new NettyRequestHandler(router, opts, shared));
        try {
            first.writeInbound(request("/work"));
            assertEquals(0, shared.availablePermits());
            second.writeInbound(request("/work"));
            FullHttpResponse overloaded = second.readOutbound();
            assertNotNull(overloaded);
            try {
                assertEquals(503, overloaded.status().code());
                assertEquals("1", overloaded.headers().get("Retry-After"));
            } finally { ReferenceCountUtil.release(overloaded); }
            slow.complete(Response.text("done"));
            first.runPendingTasks();
            FullHttpResponse done = first.readOutbound();
            assertNotNull(done);
            ReferenceCountUtil.release(done);
            assertEquals(1, shared.availablePermits());
            second.writeInbound(request("/work"));
            second.runPendingTasks();
            FullHttpResponse recovered = second.readOutbound();
            assertNotNull(recovered);
            try { assertEquals(200, recovered.status().code()); }
            finally { ReferenceCountUtil.release(recovered); }
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    @Test
    void timeoutProduces504AndNotifiesCancellation() throws Exception {
        AtomicReference<Request> captured = new AtomicReference<>();
        CompletableFuture<Response> result = new CompletableFuture<>();
        Router router = new Router().getAsync("/slow", r -> { captured.set(r); return result; });
        Semaphore permits = new Semaphore(1);
        NettyOptions opts = new NettyOptions(1, 4, 1024, Duration.ofMillis(15));
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router, opts, permits));
        try {
            channel.writeInbound(request("/slow"));
            Thread.sleep(40);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();
            FullHttpResponse timedOut = channel.readOutbound();
            assertNotNull(timedOut);
            try { assertEquals(504, timedOut.status().code()); }
            finally { ReferenceCountUtil.release(timedOut); }
            assertTrue(captured.get().cancellation().isCancelled());
            assertEquals(1, permits.availablePermits());
            result.complete(Response.text("too late"));
            channel.runPendingTasks();
            assertNull(channel.readOutbound());
            assertEquals(1, permits.availablePermits());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void disconnectCancelsRequestAndReleasesPermit() {
        AtomicReference<Request> captured = new AtomicReference<>();
        Router router = new Router().getAsync("/slow", r -> {
            captured.set(r);
            return new CompletableFuture<>();
        });
        Semaphore permits = new Semaphore(1);
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router,
                new NettyOptions(1, 4, 1024, Duration.ofSeconds(3)), permits));
        channel.writeInbound(request("/slow"));
        assertEquals(0, permits.availablePermits());
        channel.close();
        assertTrue(captured.get().cancellation().isCancelled());
        assertEquals(1, permits.availablePermits());
        channel.finishAndReleaseAll();
    }

    @Test
    void excessivePipeliningClosesChannelAndReleasesAllActiveSlots() {
        java.util.List<Request> started = new java.util.ArrayList<>();
        Router router = new Router().getAsync("/slow", request -> {
            started.add(request);
            return new CompletableFuture<>();
        });
        Semaphore permits = new Semaphore(2);
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router,
                new NettyOptions(2, 2, 1024, Duration.ofSeconds(5)), permits));
        channel.writeInbound(request("/slow"));
        channel.writeInbound(request("/slow"));
        assertEquals(0, permits.availablePermits());
        channel.writeInbound(request("/slow"));
        assertFalse(channel.isOpen());
        assertEquals(2, started.size());
        assertTrue(started.stream().allMatch(req -> req.cancellation().isCancelled()));
        assertEquals(2, permits.availablePermits());
        channel.finishAndReleaseAll();
    }

    @Test
    void mapsExpectedErrorsWithoutLeakingInternalExceptions() {
        Router router = new Router()
                .getAsync("/known", r -> CompletableFuture.failedFuture(new HttpException(422, "invalid input")))
                .getAsync("/unknown", r -> CompletableFuture.failedFuture(new IllegalStateException("DB password")));
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router));
        try {
            channel.writeInbound(request("/known"));
            channel.writeInbound(request("/unknown"));
            channel.runPendingTasks();
            FullHttpResponse known = channel.readOutbound();
            FullHttpResponse unknown = channel.readOutbound();
            assertNotNull(known);
            assertNotNull(unknown);
            try {
                assertEquals(422, known.status().code());
                assertTrue(known.content().toString(StandardCharsets.UTF_8).contains("invalid input"));
                assertEquals(500, unknown.status().code());
                assertFalse(unknown.content().toString(StandardCharsets.UTF_8).contains("DB password"));
            } finally {
                ReferenceCountUtil.release(known);
                ReferenceCountUtil.release(unknown);
            }
        } finally { channel.finishAndReleaseAll(); }
    }
}
