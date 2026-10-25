package dev.fastframe.core.http;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

final class JsonUtf8Test {
    private void parity(Object value) {
        byte[] expected = Json.stringify(value).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, Json.utf8(value));
        assertArrayEquals(expected, Response.jsonValue(value).body());
    }

    @Test void handlesUnicodeEscapesAndNestedCollections() {
        var input = new LinkedHashMap<String, Object>();
        input.put("\u00e9\ud83d\ude00", List.of("<>&\n\r\t\b\f\"\\", "\u2028\u2029", 23.5));
        input.put("null", null);
        input.put("unpaired", "\ud800/\udc00");
        input.put("symbols", new Object[]{true, false, '\u03a9', 900000000000L});
        parity(input);
    }

    @Test void matchesUtf16StringEncodingAcrossRandomValues() {
        Random random = new Random(412);
        for (int sample = 0; sample < 150; sample++) {
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < 80; i++) {
                int choice = random.nextInt(12);
                value.append(switch (choice) {
                    case 0 -> '\n'; case 1 -> '\t'; case 2 -> '<'; case 3 -> '&';
                    case 4 -> '\u2028'; case 5 -> '\ud800'; case 6 -> '\udc00';
                    case 7 -> '\u4f60'; case 8 -> '\ud83d'; case 9 -> '\ude00';
                    case 10 -> '\u00e9'; default -> 'X';
                });
            }
            parity(Map.of("payload", value.toString()));
        }
    }

    @Test void rejectsCyclesNonFiniteValuesAndOverlongPayloads() {
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> Json.utf8(cycle));
        assertThrows(IllegalArgumentException.class, () -> Json.utf8(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.utf8(Map.of(1, "invalid")));
        assertThrows(IllegalArgumentException.class, () -> Json.utf8("x".repeat(Json.MAX_CHARS)));
    }
}
