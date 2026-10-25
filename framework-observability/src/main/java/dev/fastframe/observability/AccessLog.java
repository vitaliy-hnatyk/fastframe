package dev.fastframe.observability;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Structured JSON request completion log with no body, query string, raw path, or auth headers. */
public final class AccessLog {
    private static final Logger LOGGER = Logger.getLogger("dev.fastframe.http.access");
    private AccessLog() { }
    public static void completed(String requestId, String method, int status, long durationNanos) {
        if (!LOGGER.isLoggable(Level.INFO)) return;
        String safeMethod = method.matches("[A-Z]{1,16}") ? method : "OTHER";
        // requestId comes from the server, never from a client-provided header.
        LOGGER.log(Level.INFO, () -> "{\"event\":\"http_request\",\"request_id\":\"" + requestId
                + "\",\"method\":\"" + safeMethod + "\",\"status\":" + status
                + ",\"duration_ms\":" + (durationNanos / 1_000_000.0) + "}");
    }
}
