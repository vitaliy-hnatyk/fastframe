import dev.fastframe.core.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FFV04Smoke {
    public static void main(String[] args) {
        String json = new String(Response.jsonValue(Map.of("name", "<test>\n", "ids", List.of(1, 2)))
                .body(), StandardCharsets.UTF_8);
        if (!json.contains("\\u003ctest\\u003e\\n") || !json.contains("[1,2]"))
            throw new AssertionError(json);
        List<Object> circular = new ArrayList<>(); circular.add(circular);
        try { Json.stringify(circular); throw new AssertionError("Cycle permitted"); }
        catch (IllegalArgumentException expected) { }
        try { Json.stringify(Double.NaN); throw new AssertionError("NaN permitted"); }
        catch (IllegalArgumentException expected) { }
        AtomicInteger count = new AtomicInteger();
        CancellationToken token = new CancellationToken();
        token.onCancel(count::incrementAndGet);
        token.cancel(); token.cancel(); token.onCancel(count::incrementAndGet);
        if (count.get() != 2) throw new AssertionError("Cancellation must fire once per listener");
        try { Response.text("x").header("X-Test", "bad\r\nheader");
              throw new AssertionError("Unsafe header permitted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("V0.4 CORE SMOKE PASS: JSON, cycles, cancellation, headers");
    }
}
