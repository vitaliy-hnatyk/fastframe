package dev.fastframe.netty;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

final class NettyRequestHandlerTest {
    private static DefaultFullHttpRequest get(String path) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    }


    @Test
    void octetStreamPreservesNonUtf8Bytes() {
        byte[] payload = new byte[]{0, (byte) 0xff, 7, (byte) 0x80};
        Router router = new Router().post("/binary", request ->
                Response.bytes(request.bodyBytes(), "application/octet-stream"));
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router));
        try {
            var incoming = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/binary",
                    io.netty.buffer.Unpooled.wrappedBuffer(payload));
            incoming.headers().set("Content-Type", "application/octet-stream");
            channel.writeInbound(incoming);
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response);
            try {
                assertEquals(200, response.status().code());
                byte[] actual = new byte[response.content().readableBytes()];
                response.content().getBytes(response.content().readerIndex(), actual);
                assertArrayEquals(payload, actual);
            } finally { ReferenceCountUtil.release(response); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void syncResponseIsImmediateAndDoesNotScheduleTimeout() {
        Router router = new Router().get("/instant", req -> Response.text("ok"));
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router));
        try {
            channel.writeInbound(get("/instant"));
            FullHttpResponse result = channel.readOutbound();
            assertNotNull(result);
            try {
                assertEquals(200, result.status().code());
                assertEquals("ok", result.content().toString(StandardCharsets.UTF_8));
            } finally { ReferenceCountUtil.release(result); }
            assertEquals(-1, channel.runScheduledPendingTasks(), "synchronous response must not have a timeout task");
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void queryAndPercentEncodingStillDecodeCorrectly() {
        Router router = new Router().get("/item/{id}", req ->
                Response.text(req.routeParam("id") + ":" + req.query("q")));
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router));
        try {
            channel.writeInbound(get("/item/%34%32?q=hello%20world"));
            FullHttpResponse result = channel.readOutbound();
            assertNotNull(result);
            try {
                assertEquals("42:hello world", result.content().toString(StandardCharsets.UTF_8));
            } finally { ReferenceCountUtil.release(result); }
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void asyncResponseIsWrittenAfterFutureCompletes() {
        CompletableFuture<Response> future = new CompletableFuture<>();
        Router router = new Router().getAsync("/async", req -> future);
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router));
        try {
            channel.writeInbound(get("/async"));
            assertNull(channel.readOutbound());
            future.complete(Response.text("done"));
            channel.runPendingTasks();
            FullHttpResponse result = channel.readOutbound();
            assertNotNull(result);
            try {
                assertEquals(HttpResponseStatus.OK, result.status());
                assertEquals("done", result.content().toString(StandardCharsets.UTF_8));
            } finally {
                ReferenceCountUtil.release(result);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void pipelinedResponsesKeepRequestOrder() {
        CompletableFuture<Response> slow = new CompletableFuture<>();
        Router router = new Router()
                .getAsync("/slow", req -> slow)
                .get("/fast", req -> Response.text("fast"));
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router));
        try {
            channel.writeInbound(get("/slow"));
            channel.writeInbound(get("/fast"));
            assertNull(channel.readOutbound());
            slow.complete(Response.text("slow"));
            channel.runPendingTasks();
            FullHttpResponse first = channel.readOutbound();
            FullHttpResponse second = channel.readOutbound();
            assertNotNull(first);
            assertNotNull(second);
            try {
                assertEquals("slow", first.content().toString(StandardCharsets.UTF_8));
                assertEquals("fast", second.content().toString(StandardCharsets.UTF_8));
            } finally {
                ReferenceCountUtil.release(first);
                ReferenceCountUtil.release(second);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void asyncExceptionDoesNotExposeDetails() {
        CompletableFuture<Response> future = new CompletableFuture<>();
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(
                new Router().getAsync("/error", req -> future)));
        try {
            channel.writeInbound(get("/error"));
            future.completeExceptionally(new IllegalStateException("private details"));
            channel.runPendingTasks();
            FullHttpResponse result = channel.readOutbound();
            assertNotNull(result);
            try {
                assertEquals(500, result.status().code());
                assertFalse(result.content().toString(StandardCharsets.UTF_8).contains("private details"));
            } finally {
                ReferenceCountUtil.release(result);
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
