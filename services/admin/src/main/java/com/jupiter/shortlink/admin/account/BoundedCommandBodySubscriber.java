package com.jupiter.shortlink.admin.account;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Completing the body future keeps HttpClient's request deadline active for the whole response. */
final class BoundedCommandBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
    private Flow.Subscription subscription;

    BoundedCommandBodySubscriber(int limit) {
        this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return completed;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
        if (subscription != null) {
            incoming.cancel();
            return;
        }
        subscription = incoming;
        incoming.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> chunks) {
        for (ByteBuffer chunk : chunks) {
            if (chunk.remaining() > limit - bytes.size()) {
                subscription.cancel();
                completed.completeExceptionally(
                        new IllegalStateException("Command response exceeded budget"));
                return;
            }
            byte[] copy = new byte[chunk.remaining()];
            chunk.get(copy);
            bytes.writeBytes(copy);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        completed.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        completed.complete(bytes.toByteArray());
    }
}
