package dev.fastframe.httpclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels a download before buffering more than maxBodyBytes. */
final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maxBodyBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;
    BoundedBodySubscriber(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = Objects.requireNonNull(subscription);
        if (maxBodyBytes < 0) {
            subscription.cancel();
            result.completeExceptionally(new IOException("HTTP response exceeds maxBodyBytes"));
        } else {
            subscription.request(1);
        }
    }
    @Override public void onNext(List<ByteBuffer> data) {
        if (result.isDone()) return;
        for (ByteBuffer chunk : data) {
            int count = chunk.remaining();
            if (count > maxBodyBytes - buffer.size()) {
                subscription.cancel();
                result.completeExceptionally(new IOException("HTTP response exceeds maxBodyBytes"));
                return;
            }
            byte[] bytes = new byte[count];
            chunk.get(bytes);
            buffer.write(bytes, 0, bytes.length);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable error) { result.completeExceptionally(error); }
    @Override public void onComplete() { result.complete(buffer.toByteArray()); }
}
