package dev.fastframe.netty;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.observability.HttpMetrics;
import dev.fastframe.security.CorsPolicy;
import dev.fastframe.security.HttpSecurity;
import dev.fastframe.security.IpRateLimiter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NettySecurityTest {
    private static final CorsPolicy CORS = new CorsPolicy(Set.of("https://app.example.com"),
            Set.of("GET", "POST", "OPTIONS"), Set.of("authorization", "content-type"), true, 600);

    private EmbeddedChannel channel(Router router, HttpSecurity security) {
        return new EmbeddedChannel(new NettyRequestHandler(router, NettyOptions.defaults(),
                new Semaphore(256), new HttpMetrics(), false, security));
    }

    private static FullHttpResponse invoke(EmbeddedChannel ch, HttpMethod method, String origin, String requestedMethod) {
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, "/api/test");
        if (origin != null) request.headers().set("Origin", origin);
        if (requestedMethod != null) request.headers().set("Access-Control-Request-Method", requestedMethod);
        ch.writeInbound(request);
        FullHttpResponse response = ch.readOutbound();
        assertNotNull(response);
        return response;
    }

    @Test void allowedOriginAndSecurityHeadersOnSuccess() {
        var router = new Router().get("/api/test", req -> Response.text("OK")
                .header("Access-Control-Allow-Origin", "*"));
        var ch = channel(router, new HttpSecurity(CORS, null, true, false));
        try {
            var resp = invoke(ch, HttpMethod.GET, "https://app.example.com", null);
            try {
                assertEquals(200, resp.status().code());
                assertEquals("https://app.example.com", resp.headers().get("Access-Control-Allow-Origin"));
                assertEquals("true", resp.headers().get("Access-Control-Allow-Credentials"));
                assertEquals("Origin", resp.headers().get("Vary"));
                assertEquals("nosniff", resp.headers().get("X-Content-Type-Options"));
                assertEquals("DENY", resp.headers().get("X-Frame-Options"));
                assertNull(resp.headers().get("Strict-Transport-Security"));
            } finally { ReferenceCountUtil.release(resp); }
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test void deniesDisallowedOriginBeforeControllerAndAppliesHeadersOnError() {
        AtomicInteger invoked = new AtomicInteger();
        var router = new Router().get("/api/test", req -> { invoked.incrementAndGet(); return Response.text("ok"); });
        var ch = channel(router, new HttpSecurity(CORS, null, true, false));
        try {
            var resp = invoke(ch, HttpMethod.GET, "https://evil.example", null);
            try {
                assertEquals(403, resp.status().code());
                assertNull(resp.headers().get("Access-Control-Allow-Origin"));
                assertEquals("Origin", resp.headers().get("Vary"));
                assertEquals("nosniff", resp.headers().get("X-Content-Type-Options"));
                assertEquals(0, invoked.get());
            } finally { ReferenceCountUtil.release(resp); }
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test void preflightValidAndInvalidMethod() {
        var router = new Router();
        var ch = channel(router, new HttpSecurity(CORS, null, true, false));
        try {
            var ok = invoke(ch, HttpMethod.OPTIONS, "https://app.example.com", "POST");
            try {
                assertEquals(204, ok.status().code());
                assertTrue(ok.headers().get("Access-Control-Allow-Methods").contains("POST"));
                assertEquals("600", ok.headers().get("Access-Control-Max-Age"));
            } finally { ReferenceCountUtil.release(ok); }
            var denied = invoke(ch, HttpMethod.OPTIONS, "https://app.example.com", "DELETE");
            try { assertEquals(403, denied.status().code()); }
            finally { ReferenceCountUtil.release(denied); }
        } finally { ch.finishAndReleaseAll(); }
    }

    @Test void sharedRateLimitReturns429AndRetryAfter() {
        var router = new Router().get("/api/test", req -> Response.text("ok"));
        var security = new HttpSecurity(CorsPolicy.disabled(),
                new IpRateLimiter(1, Duration.ofSeconds(20), 16), true, false);
        var ch = channel(router, security);
        try {
            var first = invoke(ch, HttpMethod.GET, null, null);
            try { assertEquals(200, first.status().code()); } finally { ReferenceCountUtil.release(first); }
            var second = invoke(ch, HttpMethod.GET, null, null);
            try {
                assertEquals(429, second.status().code());
                assertNotNull(second.headers().get("Retry-After"));
            } finally { ReferenceCountUtil.release(second); }
        } finally { ch.finishAndReleaseAll(); }
    }
}
