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
