package dev.fastframe.netty;

import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/** Netty HTTP/1.1 adapter: async completion, pipelining order, and per-channel read throttling. */
final class NettyRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final int HIGH_WATER = 64;
    private static final int LOW_WATER = 32;
    private final Router router;
    private final ArrayDeque<Pending> responses = new ArrayDeque<>();
    private boolean closing;

    NettyRequestHandler(Router router) { this.router = router; }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest incoming) {
        if (closing) return;
        Pending pending = new Pending(incoming.protocolVersion(), incoming.method().name(),
                HttpUtil.isKeepAlive(incoming));
        responses.addLast(pending);
        if (responses.size() >= HIGH_WATER) ctx.channel().config().setAutoRead(false);

        if (!incoming.decoderResult().isSuccess()) {
            finish(ctx, pending, Response.text("Bad Request").status(400));
            return;
        }
        try {
            QueryStringDecoder uri = new QueryStringDecoder(incoming.uri(), StandardCharsets.UTF_8);
            Map<String, String> headers = new HashMap<>();
            incoming.headers().forEach(entry -> headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
            // Copy the body NOW: SimpleChannelInboundHandler releases FullHttpRequest on return.
            String body = incoming.content().toString(StandardCharsets.UTF_8);
            Request request = new Request(incoming.method().name(), uri.path(), uri.parameters(), headers, body);
            CompletionStage<Response> stage = router.dispatchAsync(request);
            stage.whenComplete((response, error) -> {
                Runnable complete = () -> {
                    if (!ctx.channel().isActive()) return;
                    if (error == null && response != null) finish(ctx, pending, response);
                    else finish(ctx, pending, failure(error));
                };
                if (ctx.executor().inEventLoop()) complete.run();
                else {
                    try { ctx.executor().execute(complete); }
                    catch (RejectedExecutionException ignored) { /* channel is shutting down */ }
                }
            });
        } catch (Throwable ex) {
            finish(ctx, pending, failure(ex));
        }
    }

    private static Response failure(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof RejectedExecutionException) {
            return Response.text("Service Unavailable").status(503).header("Retry-After", "1");
        }
        // Never expose exceptions to HTTP clients.
        System.err.println("Request failed: " + cause);
        return Response.text("Internal Server Error").status(500);
    }

    private void finish(ChannelHandlerContext ctx, Pending pending, Response response) {
        pending.response = response;
        drain(ctx);
    }

    /** Drain only in request order so HTTP/1.1 pipelining stays valid. Event-loop only. */
    private void drain(ChannelHandlerContext ctx) {
        while (!responses.isEmpty() && responses.peekFirst().response != null && !closing) {
            Pending ready = responses.removeFirst();
            send(ctx, ready);
            if (!ready.keepAlive) {
                closing = true;
                responses.clear();
            }
        }
        if (!closing && responses.size() <= LOW_WATER && !ctx.channel().config().isAutoRead()) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    private static void send(ChannelHandlerContext ctx, Pending pending) {
        Response result = pending.response;
        byte[] body = result.body();
        boolean head = pending.method.equals("HEAD");
        boolean suppressBody = head || result.status() == 204 || result.status() == 304
                || (result.status() >= 100 && result.status() < 200);
        ByteBuf buffer = suppressBody ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body);
        FullHttpResponse output = new DefaultFullHttpResponse(pending.version,
                HttpResponseStatus.valueOf(result.status()), buffer);
        output.headers().set(HttpHeaderNames.CONTENT_TYPE, result.contentType());
        for (Map.Entry<String, String> header : result.headers().entrySet()) {
            // These are set by the transport, never accept competing framing headers.
            if (!header.getKey().equalsIgnoreCase("Content-Length")
                    && !header.getKey().equalsIgnoreCase("Transfer-Encoding")
                    && !header.getKey().equalsIgnoreCase("Connection")) {
                output.headers().set(header.getKey(), header.getValue());
            }
        }
        HttpUtil.setContentLength(output, head ? body.length : (suppressBody ? 0 : body.length));
        HttpUtil.setKeepAlive(output, pending.keepAlive);
        ChannelFuture sent = ctx.writeAndFlush(output);
        if (!pending.keepAlive) sent.addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        responses.clear(); // Futures can finish later; callback checks isActive().
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("Connection error: " + cause);
        ctx.close();
    }

    private static final class Pending {
        private final HttpVersion version;
        private final String method;
        private final boolean keepAlive;
        private Response response;
        private Pending(HttpVersion version, String method, boolean keepAlive) {
            this.version = version;
            this.method = method;
            this.keepAlive = keepAlive;
        }
    }
}
