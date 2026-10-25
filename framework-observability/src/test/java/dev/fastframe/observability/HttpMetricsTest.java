package dev.fastframe.observability;

import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HttpMetricsTest {
    @Test void cumulativeBucketsAndCountersAreConsistent() {
        var m = new HttpMetrics();
        m.started(); m.completed(200, 2_000_000);
        m.started(); m.completed(503, 50_000_001);
        m.started(); m.aborted();
        assertEquals(3, m.receivedRequests());
        assertEquals(2, m.completedRequests());
        assertEquals(1, m.abortedRequests());
        assertEquals(0, m.activeRequests());
        String export = m.prometheus();
        assertTrue(export.contains("fastframe_http_responses_total{status_class=\"2xx\"} 1"));
        assertTrue(export.contains("fastframe_http_responses_total{status_class=\"5xx\"} 1"));
        assertTrue(export.contains("fastframe_http_handler_duration_seconds_bucket{le=\"+Inf\"} 2"));
        assertTrue(export.contains("fastframe_http_handler_duration_seconds_count 2"));
        assertFalse(export.contains("user_id"));
    }
    @Test void readinessChangesIndependentlyFromLiveness() throws Exception {
        Router r = new Router();
        Readiness ready = new Readiness();
        OperationsRoutes.register(r, ready, new HttpMetrics(), false);
        var req = new Request("GET", "/_health/ready", Map.of(), Map.of(), "");
        assertEquals(503, ((Response) r.dispatchDirect(req)).status());
        ready.ready();
        assertEquals(200, ((Response) r.dispatchDirect(req)).status());
        ready.notReady();
        assertEquals(503, ((Response) r.dispatchDirect(req)).status());
        assertEquals(200, ((Response) r.dispatchDirect(new Request(
                "GET", "/_health/live", Map.of(), Map.of(), ""))).status());
        assertEquals(404, ((Response) r.dispatchDirect(new Request(
                "GET", "/_metrics", Map.of(), Map.of(), ""))).status());
    }
}
