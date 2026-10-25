package dev.fastframe.observability;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe, bounded-cardinality, per-server HTTP metrics. Counts handler completion, not client delivery. */
public final class HttpMetrics {
    private static final long[] BUCKETS_NS = {1_000_000L, 5_000_000L, 10_000_000L,
            25_000_000L, 50_000_000L, 100_000_000L, 250_000_000L,
            500_000_000L, 1_000_000_000L, 5_000_000_000L};
    private final AtomicLong active = new AtomicLong();
    private final LongAdder received = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder aborted = new LongAdder();
    private final LongAdder[] classes = new LongAdder[5];
    private final LongAdder[] buckets = new LongAdder[BUCKETS_NS.length + 1];
    private final LongAdder durationNanos = new LongAdder();

    public HttpMetrics() {
        for (int i = 0; i < classes.length; i++) classes[i] = new LongAdder();
        for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder();
    }
    public void started() {
        received.increment();
        active.incrementAndGet();
    }
    public void completed(int status, long elapsedNanos) {
        active.decrementAndGet();
        completed.increment();
        if (status >= 100 && status <= 599) classes[status / 100 - 1].increment();
        long elapsed = Math.max(0, elapsedNanos);
        durationNanos.add(elapsed);
        int i = 0;
        while (i < BUCKETS_NS.length && elapsed > BUCKETS_NS[i]) i++;
        buckets[i].increment();
    }
    public void aborted() {
        active.decrementAndGet();
        aborted.increment();
    }
    public long activeRequests() { return active.get(); }
    public long receivedRequests() { return received.sum(); }
    public long completedRequests() { return completed.sum(); }
    public long abortedRequests() { return aborted.sum(); }

    /** Prometheus exposition, stable label set and no user-provided URLs or identities. */
    public String prometheus() {
        StringBuilder out = new StringBuilder(1200);
        out.append("# TYPE fastframe_http_requests_received_total counter\nfastframe_http_requests_received_total ")
                .append(received.sum()).append('\n');
        out.append("# TYPE fastframe_http_requests_completed_total counter\nfastframe_http_requests_completed_total ")
                .append(completed.sum()).append('\n');
        out.append("# TYPE fastframe_http_requests_aborted_total counter\nfastframe_http_requests_aborted_total ")
                .append(aborted.sum()).append('\n');
        out.append("# TYPE fastframe_http_requests_active gauge\nfastframe_http_requests_active ")
                .append(active.get()).append('\n');
        out.append("# TYPE fastframe_http_responses_total counter\n");
        for (int i = 0; i < classes.length; i++) {
            out.append("fastframe_http_responses_total{status_class=\"").append(i + 1)
                    .append("xx\"} ").append(classes[i].sum()).append('\n');
        }
        out.append("# TYPE fastframe_http_handler_duration_seconds histogram\n");
        long cumulative = 0;
        for (int i = 0; i < BUCKETS_NS.length; i++) {
            cumulative += buckets[i].sum();
            out.append("fastframe_http_handler_duration_seconds_bucket{le=\"")
                    .append(BUCKETS_NS[i] / 1_000_000_000.0).append("\"} ")
                    .append(cumulative).append('\n');
        }
        cumulative += buckets[buckets.length - 1].sum();
        out.append("fastframe_http_handler_duration_seconds_bucket{le=\"+Inf\"} ")
                .append(cumulative).append('\n');
        out.append("fastframe_http_handler_duration_seconds_sum ")
                .append(durationNanos.sum() / 1_000_000_000.0).append('\n');
        out.append("fastframe_http_handler_duration_seconds_count ")
                .append(completed.sum()).append('\n');
        return out.toString();
    }
}
