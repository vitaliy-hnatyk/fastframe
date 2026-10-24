package dev.fastframe.core.http;

import java.util.Objects;

/** Expected client-facing error. Never include secrets in publicMessage. */
public final class HttpException extends RuntimeException {
    private final int status;
    private final String publicMessage;

    public HttpException(int status, String publicMessage) {
        super(Objects.requireNonNull(publicMessage, "publicMessage"));
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("HTTP exception status must be 4xx or 5xx");
        }
        this.status = status;
        this.publicMessage = publicMessage;
    }

    public int status() { return status; }
    public String publicMessage() { return publicMessage; }
}
