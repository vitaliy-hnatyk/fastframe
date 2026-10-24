package dev.fastframe.netty;

import dev.fastframe.core.http.HttpException;
import dev.fastframe.core.http.ValidationException;
import dev.fastframe.core.http.Response;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Public-safe error response mapping, separate from internal exception logging. */
final class ErrorResponses {
    private static final Logger LOG = Logger.getLogger(ErrorResponses.class.getName());

    private ErrorResponses() { }

    static Response from(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof ValidationException validation) {
            return Response.jsonValue(Map.of("error", Map.of("code", "validation_failed",
                    "message", "Validation failed", "fields", validation.errors()))).status(422);
        }
        if (cause instanceof HttpException http) {
            return of(http.status(), "http_error", http.publicMessage());
        }
        if (cause instanceof RejectedExecutionException) {
            return of(503, "overloaded", "Service Unavailable").header("Retry-After", "1");
        }
        LOG.log(Level.WARNING, "Unhandled request exception", cause);
        return of(500, "internal_error", "Internal Server Error");
    }

    static Response badRequest() { return of(400, "bad_request", "Bad Request"); }
    static Response overloaded() {
        return of(503, "overloaded", "Service Unavailable").header("Retry-After", "1");
    }
    static Response timeout() { return of(504, "timeout", "Gateway Timeout"); }

    private static Response of(int status, String code, String message) {
        return Response.jsonValue(Map.of("error", Map.of("code", code, "message", message))).status(status);
    }
}
