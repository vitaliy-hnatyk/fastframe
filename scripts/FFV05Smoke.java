package dev.fastframe.netty;

import dev.fastframe.core.http.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** JDK-only parser and public HTTP error response verification. */
public final class FFV05Smoke {
    static void ensure(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static JsonBody json(String body) {
        return JsonBody.parse(new Request("POST", "/", Map.of(), Map.of("content-type", "application/json"), body));
    }
    static void malformed(String body) {
        try { json(body); throw new AssertionError("Accepted malformed JSON: " + body); }
        catch (HttpException ex) { ensure(ex.status() == 400, "malformed JSON status"); }
    }
    public static void main(String[] args) {
        JsonBody input = json("{\"name\":\"H\\u00e9lo \\uD83D\\uDE80\",\"count\":10,\"active\":true}");
        ensure(input.string("name", true, 2, 99).equals("Hélo 🚀"), "decoded unicode");
        ensure(input.integer("count", true, 0, 100) == 10, "decoded integer");
        ensure(input.bool("active", true), "decoded boolean");
        input.finish("name", "count", "active");
        for (String sample : List.of("{\"x\":1,\"x\":2}", "{\"x\":01}", "{\"x\":true}junk",
                "{\"x\":\"\\uD800\"}", "{\"x\":1,}", "{\"x\":\"\\xFF\"}",
                "{\"x\":" + "[".repeat(40) + "null" + "]".repeat(40) + "}")) malformed(sample);
        JsonBody invalid = json("{\"name\":\"x\",\"age\":9,\"extra\":true}");
        invalid.string("name", true, 3, 50);
        invalid.integer("age", true, 18, 100);
        try { invalid.finish("name", "age"); throw new AssertionError("No validation error"); }
        catch (ValidationException ex) {
            ensure(ex.errors().size() == 3, "combined field errors");
            Response response = ErrorResponses.from(ex);
            String text = new String(response.body(), StandardCharsets.UTF_8);
            ensure(response.status() == 422 && text.contains("validation_failed") && text.contains("extra"),
                    "422 structured response");
        }
        ensure(ErrorResponses.from(new HttpException(415, "Use JSON")).status() == 415, "415 status");
        System.out.println("V0.5 JSON SMOKE PASS: parser, validation, 400/415/422 error mappings");
    }
}
