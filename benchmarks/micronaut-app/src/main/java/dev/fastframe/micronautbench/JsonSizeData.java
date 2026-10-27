package dev.fastframe.micronautbench;

import java.util.Map;

/** Benchmark fixture. Values are precomputed; JSON serialization happens per request. */
public final class JsonSizeData {
    private static final Map<String, Integer> LENGTHS = Map.of(
            "1kb", 1_024, "10kb", 10_240, "100kb", 102_400, "1mb", 1_048_576);
    private static final Map<String, Map<String, String>> DOCUMENTS = Map.of(
            "1kb", document(1_024),
            "10kb", document(10_240),
            "100kb", document(102_400),
            "1mb", document(1_048_576));

    private JsonSizeData() { }

    private static Map<String, String> document(int bytes) {
        // {"data":""} is 11 ASCII bytes. Payload consists solely of ASCII 'x'.
        return Map.of("data", "x".repeat(bytes - 11));
    }

    public static Map<String, String> get(String size) {
        return LENGTHS.containsKey(size) ? DOCUMENTS.get(size) : null;
    }
}
