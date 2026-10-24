package dev.fastframe.core.http;

import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonBodyTest {
    private static JsonBody parse(String body) {
        return JsonBody.parse(new Request("POST", "/test", Map.of(),
                Map.of("content-type", "application/json"), body));
    }
    @Test void supportsEscapesUnicodeAndNumbers() {
        JsonBody json = parse("{\"name\":\"M\\u00e1ria\",\"age\":42,\"active\":true}");
        assertEquals("Mária", json.string("name", true, 1, 20));
        assertEquals(42, json.integer("age", true, 0, 150));
        assertEquals(true, json.bool("active", true));
        assertDoesNotThrow(() -> json.finish("name", "age", "active"));
    }
    @Test void reportsFieldErrorsAndUnknownFields() {
        JsonBody json = parse("{\"name\":\"x\",\"age\":1,\"unknown\":true}");
        json.string("name", true, 2, 20);
        json.integer("age", true, 18, 140);
        ValidationException error = assertThrows(ValidationException.class,
                () -> json.finish("name", "age"));
        assertEquals(3, error.errors().size());
    }
    @Test void rejectsMalformedJson() {
        for (String body : new String[] {"{", "{\"x\":1,\"x\":2}", "{\"a\":01}",
                "{\"s\":\"\\uD800\"}", "{\"x\":1}more", "{\"x\":1,}"}) {
            assertEquals(400, assertThrows(HttpException.class, () -> parse(body)).status(), body);
        }
    }
    @Test void requiresJsonContentType() {
        assertEquals(415, assertThrows(HttpException.class,
                () -> JsonBody.parse(new Request("POST", "/", Map.of(), Map.of(), "{}"))).status());
    }
    @Test void refusesDepthAbuse() {
        String deep = "[".repeat(40) + "null" + "]".repeat(40);
        assertEquals(400, assertThrows(HttpException.class,
                () -> parse("{\"x\":" + deep + "}")).status());
    }
}
