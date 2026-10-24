package dev.fastframe.netty;

import java.time.Duration;
import java.util.Objects;

/** Limits shared by the Netty HTTP adapter. Use explicit tuning after load tests. */
public record NettyOptions(int maxConcurrentRequests, int maxPipelinedRequestsPerConnection,
                           int maxRequestBytes, Duration requestTimeout) {
    public NettyOptions {
        if (maxConcurrentRequests < 1) throw new IllegalArgumentException("maxConcurrentRequests must be positive");
        if (maxPipelinedRequestsPerConnection < 1) throw new IllegalArgumentException("maxPipelinedRequestsPerConnection must be positive");
        if (maxRequestBytes < 1) throw new IllegalArgumentException("maxRequestBytes must be positive");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        try { requestTimeout.toNanos(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("requestTimeout is too large", e); }
    }

    public static NettyOptions defaults() {
        return new NettyOptions(256, 64, 64 * 1024, Duration.ofSeconds(10));
    }
}
