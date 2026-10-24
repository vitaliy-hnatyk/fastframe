package dev.fastframe.core.http;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public validation errors, safe for rendering to HTTP clients. */
public final class ValidationException extends RuntimeException {
    private final Map<String, List<String>> errors;

    public ValidationException(Map<String, List<String>> errors) {
        super("Validation failed");
        Objects.requireNonNull(errors);
        var copy = new java.util.LinkedHashMap<String, List<String>>();
        errors.forEach((name, messages) -> copy.put(name, List.copyOf(messages)));
        this.errors = java.util.Collections.unmodifiableMap(copy);
    }

    public Map<String, List<String>> errors() { return errors; }
}
