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
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Event-loop-confined HTTP/1.1 adapter. Shares a request semaphore across server channels. */
final class NettyRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final Router router;
    private final NettyOptions options;
    private final Semaphore permits;
    private final ArrayDeque<Pending> responses = new ArrayDeque<>();
    private boolean closing;

    NettyRequestHandler(Router router) {
        this(router, NettyOptions.defaults(), new Semaphore(NettyOptions.defaults().maxConcurrentRequests()));
    }

    NettyRequestHandler(Router router, NettyOptions options, Semaphore permits) {
        this.router = Objects.requireNonNull(router, "router");
        this.options = Objects.requireNonNull(options, "options");
        this.permits = Objects.requireNonNull(permits, "permits");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest incoming) {
        if (closing) return;
        // AutoRead is advisory (a single socket read can contain many pipelined requests).
        // Enforce an absolute bound even when additional requests were already decoded.
        if (responses.size() >= options.maxPipelinedRequestsPerConnection()) {
            closing = true;
            abandonQueued();
            ctx.close();
            return;
        }
        Pending pending = new Pending(incoming.protocolVersion(), incoming.method().name(),
                HttpUtil.isKeepAlive(incoming));
        responses.addLast(pending);
        if (responses.size() >= options.maxPipelinedRequestsPerConnection()) {
            ctx.channel().config().setAutoRead(false);
        }

        if (!incoming.decoderResult().isSuccess()) {
            finish(ctx, pending, ErrorResponses.badRequest());
            return;
        }
        if (!permits.tryAcquire()) {
            finish(ctx, pending, ErrorResponses.overloaded());
            return;
        }
        pending.hasPermit = true;
        try {
            // Most benchmark and API paths are already plain ASCII without a query.
            // Keep QueryStringDecoder for percent-encoded, query or absolute URIs.
            String rawUri = incoming.uri();
            boolean plainPath = rawUri.startsWith("/") && rawUri.indexOf('?') < 0
                    && rawUri.indexOf('%') < 0 && rawUri.indexOf('#') < 0;
            QueryStringDecoder uri = plainPath ? null : new QueryStringDecoder(rawUri, StandardCharsets.UTF_8);
            String path = plainPath ? rawUri : uri.path();
            Map<String, java.util.List<String>> queries = plainPath ? Map.of() : uri.parameters();
            Map<String, String> headers = new HashMap<>(incoming.headers().size());
            incoming.headers().forEach(entry ->
                    headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue()));
            // FullHttpRequest is auto-released on method exit; copy the body before handing it off.
            // Reject malformed UTF-8 instead of silently replacing it (important for JSON).
            String decoded;
            if (!incoming.content().isReadable()) {
                decoded = ""; // avoid allocating a decoder for empty request bodies
            } else {
                ByteBuffer input = incoming.content().nioBuffer();
                try {
                    decoded = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(input).toString();
                } catch (CharacterCodingException invalidUtf8) {
                    finish(ctx, pending, ErrorResponses.badRequest());
                    return;
                }
            }
            Request request = new Request(incoming.method().name(), path, queries, headers, decoded);
            pending.request = request;

            Object result = router.dispatchDirect(request);
            if (result instanceof Response synchronousResponse) {
                // Sync route: no CompletionStage, callback registration or scheduled timeout.
                finish(ctx, pending, synchronousResponse);
                return;
            }
            @SuppressWarnings("unchecked")
            CompletionStage<Response> stage = (CompletionStage<Response>) result;
            stage.whenComplete((response, error) -> {
                Runnable completion = () -> {
                    if (!ctx.channel().isActive() || pending.finished) return;
                    if (error == null && response != null) finish(ctx, pending, response);
                    else finish(ctx, pending, ErrorResponses.from(error != null ? error :
                            new NullPointerException("Controller returned null response")));
                };
                if (ctx.executor().inEventLoop()) completion.run();
                else {
                    try { ctx.executor().execute(completion); }
                    catch (RejectedExecutionException ignored) { /* channel is shutting down */ }
                }
            });
            // The immediate completion case also avoids scheduling a timer. Pending
            // asynchronous requests still get the same timeout and cancellation.
            if (!pending.finished) {
                pending.timeout = ctx.executor().schedule(() -> {
                    if (!pending.finished) {
                        finish(ctx, pending, ErrorResponses.timeout());
                        request.cancellation().cancel();
                    }
                }, options.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (Throwable ex) {
            finish(ctx, pending, ErrorResponses.from(ex));
        }
    }

    /** Must run on this channel's event loop. */
    private void finish(ChannelHandlerContext ctx, Pending pending, Response response) {
        if (pending.finished) return; // timeout, completion and close may race
        pending.finished = true;
        pending.response = response;
        release(pending, false);
        drain(ctx);
    }

    private void release(Pending pending, boolean cancelRequest) {
        if (pending.timeout != null) {
            pending.timeout.cancel(false);
            pending.timeout = null;
        }
        if (cancelRequest && pending.request != null) pending.request.cancellation().cancel();
        if (pending.hasPermit) {
            pending.hasPermit = false;
            permits.release();
        }
    }

    private void abandonQueued() {
        for (Pending pending : responses) {
            if (!pending.finished) {
                pending.finished = true;
                release(pending, true);
            }
        }
        responses.clear();
    }

    /** Drain only in request order to preserve HTTP/1.1 pipelining. */
    private void drain(ChannelHandlerContext ctx) {
        while (!responses.isEmpty() && responses.peekFirst().response != null && !closing) {
            Pending ready = responses.removeFirst();
            send(ctx, ready);
            if (!ready.keepAlive) {
                closing = true;
                abandonQueued();
            }
        }
        if (!closing && responses.size() <= options.maxPipelinedRequestsPerConnection() / 2
                && !ctx.channel().config().isAutoRead()) {
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
        closing = true;
        abandonQueued();
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Transport errors are not application 500 responses; connection is no longer reliable.
        ctx.close();
    }

    private static final class Pending {
        private final HttpVersion version;
        private final String method;
        private final boolean keepAlive;
        private Response response;
        private Request request;
        private ScheduledFuture<?> timeout;
        private boolean finished;
        private boolean hasPermit;

        private Pending(HttpVersion version, String method, boolean keepAlive) {
            this.version = version;
            this.method = method;
            this.keepAlive = keepAlive;
        }
    }
}
