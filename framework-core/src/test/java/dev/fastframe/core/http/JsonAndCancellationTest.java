package dev.fastframe.core.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class JsonAndCancellationTest {
    @Test
    void jsonValueEscapesUntrustedValuesAndPreservesTypes() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "<script>\"\n");
        input.put("ok", true);
        input.put("items", List.of(1, 2, 3));
        String body = new String(Response.jsonValue(input).body(), StandardCharsets.UTF_8);
        assertEquals("{\"name\":\"\\u003cscript\\u003e\\\"\\n\",\"ok\":true,\"items\":[1,2,3]}", body);
        assertEquals("\"\\ufffd\"", Json.quote("\ud800"));
        assertEquals("\"😀\"", Json.quote("😀"));
    }

    @Test
    void rejectsCyclesUnsupportedTypesNonFiniteNumbersAndExcessiveOutput() {
        List<Object> loop = new ArrayList<>();
        loop.add(loop);
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(loop));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(new Object()));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of(1, "invalid key")));
        assertThrows(IllegalArgumentException.class, () -> Json.quote("x".repeat(Json.MAX_CHARS)));
    }

    @Test
    void cancellationIsIdempotentAndLateCallbacksRunOnce() {
        CancellationToken token = new CancellationToken();
        AtomicInteger count = new AtomicInteger();
        token.onCancel(count::incrementAndGet);
        token.onCancel(() -> { throw new IllegalStateException("bad callback"); });
        token.onCancel(count::incrementAndGet);
        token.cancel();
        token.cancel();
        token.onCancel(count::incrementAndGet);
        assertEquals(3, count.get());
        assertTrue(token.isCancelled());
    }

    @Test
    void preventsHeaderInjection() {
        assertThrows(IllegalArgumentException.class,
                () -> Response.text("ok").header("X-Example", "ok\r\nInjected: yes"));
        assertThrows(IllegalArgumentException.class,
                () -> Response.text("ok").header("X-Example", "ok\u0000bad"));
    }
}
