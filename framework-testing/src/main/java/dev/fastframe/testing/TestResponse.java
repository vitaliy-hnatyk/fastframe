package dev.fastframe.testing;

import dev.fastframe.core.http.JsonBody;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fluent assertions on responses received over the real HTTP/1.1 Netty transport. */
public final class TestResponse {
    private final HttpResponse<byte[]> response;
    TestResponse(HttpResponse<byte[]> response) { this.response = Objects.requireNonNull(response); }
    public int status() { return response.statusCode(); }
    public String body() { return new String(response.body(), StandardCharsets.UTF_8); }
    public String header(String name) { return response.headers().firstValue(name).orElse(null); }
    public TestResponse assertStatus(int expected) {
        if (status() != expected) throw new AssertionError("Expected HTTP " + expected + ", got " + status() + ": " + body());
        return this;
    }
    public TestResponse assertHeader(String name, String expected) {
        if (!Objects.equals(header(name), expected))
            throw new AssertionError("Header " + name + " expected " + expected + ", got " + header(name));
        return this;
    }
    public TestResponse assertBody(String expected) {
        if (!Objects.equals(body(), expected)) throw new AssertionError("Body mismatch: " + body());
        return this;
    }
    public TestResponse assertJson(String expectedJson) {
        if (!equalsJson(json(), JsonBody.parseValue(expectedJson)))
            throw new AssertionError("JSON mismatch: " + body());
        return this;
    }
    public TestResponse assertJsonPath(String path, Object expected) {
        Object actual = at(json(), path);
        if (!equalsJson(actual, expected))
            throw new AssertionError("JSON path " + path + " expected " + expected + ", got " + actual);
        return this;
    }
    private Object json() {
        try { return JsonBody.parseValue(body()); }
        catch (RuntimeException ex) { throw new AssertionError("Expected valid JSON: " + body(), ex); }
    }
    private static boolean equalsJson(Object a, Object b) {
        if (a instanceof Number left && b instanceof Number right) {
            try { return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0; }
            catch (NumberFormatException ex) { return false; }
        }
        if (a instanceof Map<?, ?> left && b instanceof Map<?, ?> right) {
            if (!left.keySet().equals(right.keySet())) return false;
            for (Object key : left.keySet()) if (!equalsJson(left.get(key), right.get(key))) return false;
            return true;
        }
        if (a instanceof List<?> left && b instanceof List<?> right) {
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++) if (!equalsJson(left.get(i), right.get(i))) return false;
            return true;
        }
        return Objects.equals(a, b);
    }
    /** Supports $.user.name and $.users[0].id (not arbitrary JSONPath filters). */
    private static Object at(Object root, String path) {
        if (path == null || !path.startsWith("$") || path.length() > 256) throw new IllegalArgumentException("Invalid JSON path");
        int i = 1;
        Object current = root;
        while (i < path.length()) {
            if (path.charAt(i) == '.') {
                int start = ++i;
                while (i < path.length() && (Character.isLetterOrDigit(path.charAt(i)) || path.charAt(i) == '_')) i++;
                if (i == start || !(current instanceof Map<?, ?> map) || !map.containsKey(path.substring(start, i)))
                    throw new AssertionError("Missing JSON key in " + path);
                current = map.get(path.substring(start, i));
            } else if (path.charAt(i) == '[') {
                int start = ++i;
                while (i < path.length() && Character.isDigit(path.charAt(i))) i++;
                if (i == start || i == path.length() || path.charAt(i++) != ']' || !(current instanceof List<?> list))
                    throw new AssertionError("Invalid array reference in " + path);
                int index;
                try { index = Integer.parseInt(path.substring(start, i-1)); }
                catch (NumberFormatException ex) { throw new AssertionError("Invalid array index", ex); }
                if (index >= list.size()) throw new AssertionError("Missing JSON array index " + index);
                current = list.get(index);
            } else throw new IllegalArgumentException("Unsupported JSONPath syntax: " + path);
        }
        return current;
    }
}
