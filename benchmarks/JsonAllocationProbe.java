// Small diagnostic, NOT a replacement for JMH/JFR or end-to-end load tests.
import dev.fastframe.core.http.Json;
import dev.fastframe.core.http.Response;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import com.sun.management.ThreadMXBean;

public class JsonAllocationProbe {
    private static volatile int sink;
    private static final Map<String, Object> PAYLOAD = new LinkedHashMap<>();
    static {
        PAYLOAD.put("message", "Hello, FastFrame 😀 < &");
        PAYLOAD.put("count", 42);
        PAYLOAD.put("ok", true);
    }
    private static void oldPath() { sink = Response.json(Json.stringify(PAYLOAD)).body().length; }
    private static void newPath() { sink = Response.jsonValue(PAYLOAD).body().length; }
    private static double allocatedBytes(ThreadMXBean bean, boolean optimized, int loops) {
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < loops; i++) {
            if (optimized) newPath(); else oldPath();
        }
        return (double)(bean.getThreadAllocatedBytes(thread) - before) / loops;
    }
    public static void main(String[] args) {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            System.err.println("Thread allocation measurement unavailable on this JVM");
            return;
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int i = 0; i < 100_000; i++) { oldPath(); newPath(); }
        // Alternate ordering to limit JIT/measurement-order bias.
        int n = 200_000;
        double old1 = allocatedBytes(bean, false, n);
        double new1 = allocatedBytes(bean, true, n);
        double new2 = allocatedBytes(bean, true, n);
        double old2 = allocatedBytes(bean, false, n);
        System.out.printf(java.util.Locale.ROOT,
                "jsonValue allocation diagnostic: old=%.1f B/call, new=%.1f B/call, delta=%.1f%%%n",
                (old1 + old2)/2, (new1+new2)/2,
                ((new1 + new2)/(old1 + old2)-1)*100);
        System.out.println("Diagnostic only: rerun with the same JDK, heap and payload; measure RPS separately.");
    }
}
