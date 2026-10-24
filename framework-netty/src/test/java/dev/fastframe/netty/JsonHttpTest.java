package dev.fastframe.netty;

import dev.fastframe.core.http.HttpException;
import dev.fastframe.core.http.ValidationException;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class JsonHttpTest {
    @Test void mapsValidationTo422WithoutStackTrace() {
        var error = new ValidationException(Map.of("name", List.of("is required")));
        Response result = ErrorResponses.from(error);
        assertEquals(422, result.status());
        String body = new String(result.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("validation_failed"));
        assertTrue(body.contains("name"));
        assertFalse(body.contains("java.lang"));
    }

    @Test void mapsUnsupportedContentTypeTo415() {
        assertEquals(415, ErrorResponses.from(new HttpException(415, "Use JSON")).status());
    }

    @Test void rejectsMalformedUtf8InBody() {
        Router router = new Router().post("/json", request -> Response.jsonValue(Map.of("ok", true)));
        EmbeddedChannel channel = new EmbeddedChannel(new NettyRequestHandler(router));
        try {
            var incoming = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/json",
                    Unpooled.wrappedBuffer(new byte[] {(byte)0xC3, (byte)0x28}));
            incoming.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            channel.writeInbound(incoming);
            channel.runPendingTasks();
            FullHttpResponse result = channel.readOutbound();
            assertNotNull(result);
            try { assertEquals(400, result.status().code()); }
            finally { ReferenceCountUtil.release(result); }
        } finally { channel.finishAndReleaseAll(); }
    }
}
