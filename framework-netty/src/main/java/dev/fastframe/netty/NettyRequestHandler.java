package dev.fastframe.netty;

import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.observability.HttpMetrics;
import dev.fastframe.observability.AccessLog;
import dev.fastframe.security.HttpSecurity;
import dev.fastframe.security.CorsPolicy;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Event-loop-confined HTTP/1.1 adapter. Shares a request semaphore across server channels. */
final class NettyRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String BINARY_MEDIA_TYPE = "application/octet-stream";
    private static final String[] CORS_CONTROLLED_HEADERS = {
            "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials",
            "Access-Control-Allow-Methods", "Access-Control-Allow-Headers",
            "Access-Control-Max-Age", "Access-Control-Expose-Headers"
    };

    private final Router router;
    private final NettyOptions options;
    private final Semaphore permits;
    private final HttpMetrics metrics;
    private final boolean accessLog;
    private final HttpSecurity security;
    private boolean draining;
    private final ArrayDeque<Pending> responses = new ArrayDeque<>();
    private boolean closing;

    NettyRequestHandler(Router router) {
        this(router, NettyOptions.defaults(), new Semaphore(NettyOptions.defaults().maxConcurrentRequests()));
    }

    NettyRequestHandler(Router router, NettyOptions options, Semaphore permits) {
        this(router, options, permits, new HttpMetrics(), false);
    }

    NettyRequestHandler(Router router, NettyOptions options, Semaphore permits,
                        HttpMetrics metrics, boolean accessLog) {
        this(router, options, permits, metrics, accessLog, HttpSecurity.defaults());
    }

    NettyRequestHandler(Router router, NettyOptions options, Semaphore permits,
                        HttpMetrics metrics, boolean accessLog, HttpSecurity security) {
        this.security = Objects.requireNonNull(security, "security");
        this.router = Objects.requireNonNull(router, "router");
        this.options = Objects.requireNonNull(options, "options");
        this.permits = Objects.requireNonNull(permits, "permits");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.accessLog = accessLog;
    }

    /** Called on the channel event loop after the listener stops accepting connections. */
    void beginDrain(Channel channel) {
        draining = true;
        channel.config().setAutoRead(false);
        if (responses.isEmpty()) channel.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest incoming) {
        if (closing || draining) { ctx.close(); return; }
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
        String upgrade=incoming.headers().get(HttpHeaderNames.UPGRADE);
        if(upgrade!=null&&upgrade.equalsIgnoreCase("websocket"))pending.upgradeRequest=incoming.retainedDuplicate();
        metrics.started();
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
            CorsPolicy cors = security.cors();
            String origin = incoming.headers().get("Origin");
            if (cors.enabled() && origin != null) {
                if (!cors.allowsOrigin(origin)) {
                    finish(ctx, pending, Response.text("Forbidden").status(403));
                    return;
                }
                pending.allowedOrigin = origin;
            }
            if (incoming.method().name().equals("OPTIONS")
                    && incoming.headers().contains("Access-Control-Request-Method")) {
                if (!cors.enabled() || pending.allowedOrigin == null
                        || !cors.allowsPreflight(incoming.headers().get("Access-Control-Request-Method"),
                                               incoming.headers().get("Access-Control-Request-Headers"))) {
                    finish(ctx, pending, Response.text("Forbidden").status(403));
                    return;
                }
                pending.preflight = true;
                finish(ctx, pending, Response.text("").status(204));
                return;
            }
            if (security.limiter() != null) {
                var remote = ctx.channel().remoteAddress();
                String client = remote instanceof InetSocketAddress addr && addr.getAddress() != null
                        ? addr.getAddress().getHostAddress() : "unknown";
                long retryAfter = security.limiter().acquire(client);
                if (retryAfter > 0) {
                    finish(ctx, pending, Response.text("Too Many Requests").status(429)
                            .header("Retry-After", Long.toString(retryAfter)));
                    return;
                }
            }
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
            byte[] binaryBody = null;
            String contentType = incoming.headers().get(HttpHeaderNames.CONTENT_TYPE);
            boolean binary = isBinaryContentType(contentType);
            if (binary) {
                binaryBody = new byte[incoming.content().readableBytes()];
                incoming.content().getBytes(incoming.content().readerIndex(), binaryBody);
                decoded = "";
            } else if (!incoming.content().isReadable()) {
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
            Request request = new Request(incoming.method().name(), path, queries, headers,
                    decoded, binaryBody, pending.requestId);
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
        long elapsed = Math.max(0L, System.nanoTime() - pending.startedNanos);
        metrics.completed(response.status(), elapsed);
        if (accessLog) AccessLog.completed(pending.requestId, pending.method, response.status(), elapsed);
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
        if(cancelRequest)releaseUpgrade(pending);
    }

    private void abandonQueued() {
        for (Pending pending : responses) {
            if (!pending.finished) {
                pending.finished = true;
                metrics.aborted();
                release(pending, true);
            }
        }
        responses.clear();
    }

    /** Drain only in request order to preserve HTTP/1.1 pipelining. */
    private void drain(ChannelHandlerContext ctx) {
        while (!responses.isEmpty() && responses.peekFirst().response != null && !closing) {
            Pending ready = responses.removeFirst();
            boolean closeAfter = !ready.keepAlive || (draining && responses.isEmpty());
            send(ctx, ready, closeAfter);
            if (!ready.keepAlive) {
                closing = true;
                abandonQueued();
            }
        }
        if (!closing && !draining && responses.size() <= options.maxPipelinedRequestsPerConnection() / 2
                && !ctx.channel().config().isAutoRead()) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    private void send(ChannelHandlerContext ctx, Pending pending, boolean closeAfter) {
        Response result = pending.response;
        if(result.sseSource()!=null){startSse(ctx,pending,result);return;}
        if(result.webSocketHandler()!=null){startWebSocket(ctx,pending,result);return;}
        releaseUpgrade(pending);
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
        // Do not allow controllers to opt out of the server CORS policy.
        for (String header : CORS_CONTROLLED_HEADERS) output.headers().remove(header);
        output.headers().set("X-Request-ID", pending.requestId);
        if (security.secureHeaders()) {
            // Override application values: security headers cannot be weakened by a controller.
            output.headers().set("X-Content-Type-Options", "nosniff");
            output.headers().set("X-Frame-Options", "DENY");
            output.headers().set("Referrer-Policy", "no-referrer");
            if (security.hsts()) {
                // Only enable when the externally visible site is HTTPS-only.
                output.headers().set("Strict-Transport-Security", "max-age=31536000");
            }
        }
        if (security.cors().enabled()) {
            // Include Vary even for denied/no-Origin responses to prevent shared-cache confusion.
            String vary = output.headers().get("Vary");
            if (vary == null) output.headers().set("Vary", "Origin");
            else if (!hasVaryOrigin(vary))
                output.headers().set("Vary", vary + ", Origin");
        }
        if (pending.allowedOrigin != null) {
            CorsPolicy cors = security.cors();
            output.headers().set("Access-Control-Allow-Origin", pending.allowedOrigin);
            if (cors.allowCredentials()) output.headers().set("Access-Control-Allow-Credentials", "true");
            output.headers().set("Access-Control-Expose-Headers", "X-Request-ID");
            if (pending.preflight) {
                output.headers().set("Access-Control-Allow-Methods", cors.methodsHeader());
                output.headers().set("Access-Control-Allow-Headers", cors.headersHeader());
                output.headers().set("Access-Control-Max-Age", Integer.toString(cors.maxAgeSeconds()));
            }
        }
        HttpUtil.setContentLength(output, head ? body.length : (suppressBody ? 0 : body.length));
        if(!(ctx.channel() instanceof Http2StreamChannel))HttpUtil.setKeepAlive(output, !closeAfter);
        ChannelFuture sent = ctx.writeAndFlush(output);
        if (closeAfter) sent.addListener(ChannelFutureListener.CLOSE);
    }

    private static void releaseUpgrade(Pending p){if(p.upgradeRequest!=null){p.upgradeRequest.release();p.upgradeRequest=null;}}
    private void startSse(ChannelHandlerContext ctx,Pending p,Response response){
       releaseUpgrade(p);
       if(!p.method.equals("GET")){ctx.close();return;}
       HttpResponse reply=new DefaultHttpResponse(p.version,HttpResponseStatus.OK);
       reply.headers().set(HttpHeaderNames.CONTENT_TYPE,"text/event-stream; charset=utf-8");
       reply.headers().set(HttpHeaderNames.CACHE_CONTROL,"no-cache, no-transform");
       reply.headers().set("X-Accel-Buffering","no");
       reply.headers().set("X-Content-Type-Options","nosniff");
       reply.headers().set("X-Request-ID",p.requestId);
       if(!(ctx.channel() instanceof Http2StreamChannel))HttpUtil.setTransferEncodingChunked(reply,true);
       ctx.writeAndFlush(reply);
       closing=true;ctx.channel().config().setAutoRead(false);
       var sink=new SseChannel(ctx.channel());
       try{response.sseSource().subscribe(sink);}catch(Throwable ex){sink.close();}
    }
    private void startWebSocket(ChannelHandlerContext ctx,Pending p,Response response){
       FullHttpRequest upgrade=p.upgradeRequest;p.upgradeRequest=null;
       if(upgrade==null||!p.method.equals("GET")||ctx.channel() instanceof Http2StreamChannel||!responses.isEmpty()){
          if(upgrade!=null)upgrade.release();ctx.close();return;
       }
       try{
         boolean tls=ctx.pipeline().get(io.netty.handler.ssl.SslHandler.class)!=null;
         String host=upgrade.headers().get(HttpHeaderNames.HOST);
         if(host==null||host.isBlank())host="localhost";
         var factory=new WebSocketServerHandshakerFactory((tls?"wss://":"ws://")+host+upgrade.uri(),null,true,options.maxRequestBytes());
         WebSocketServerHandshaker handshake=factory.newHandshaker(upgrade);
         if(handshake==null){WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());return;}
         handshake.handshake(ctx.channel(),upgrade).addListener(f->{
             if(!f.isSuccess()){ctx.close();return;}
             ctx.pipeline().replace(this,"fastframe-websocket",new WebSocketFrames(response.webSocketHandler()));
             ctx.pipeline().addBefore("fastframe-websocket","fastframe-websocket-aggregator",new WebSocketFrameAggregator(options.maxRequestBytes()));
         });
       }catch(Throwable ex){ctx.close();}finally{upgrade.release();}
    }

    // Character scan avoids regex/split/list allocations for ordinary content types.
    static boolean isBinaryContentType(String contentType) {
        if (contentType == null) return false;
        int start = 0;
        int end = contentType.length();
        while (start < end && Character.isWhitespace(contentType.charAt(start))) start++;
        if (!contentType.regionMatches(true, start, BINARY_MEDIA_TYPE, 0, BINARY_MEDIA_TYPE.length()))
            return false;
        int next = start + BINARY_MEDIA_TYPE.length();
        while (next < end && Character.isWhitespace(contentType.charAt(next))) next++;
        return next == end || contentType.charAt(next) == ';';
    }

    private static boolean hasVaryOrigin(String vary) {
        int start = 0;
        while (start < vary.length()) {
            int end = vary.indexOf(',', start);
            if (end < 0) end = vary.length();
            int from = start, to = end;
            while (from < to && Character.isWhitespace(vary.charAt(from))) from++;
            while (to > from && Character.isWhitespace(vary.charAt(to - 1))) to--;
            if (to - from == 6 && vary.regionMatches(true, from, "Origin", 0, 6)) return true;
            start = end + 1;
        }
        return false;
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
        private final String requestId = UUID.randomUUID().toString();
        private final long startedNanos = System.nanoTime();
        private final HttpVersion version;
        private final String method;
        private final boolean keepAlive;
        private Response response;
        private Request request;
        private FullHttpRequest upgradeRequest;
        private String allowedOrigin;
        private boolean preflight;
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
