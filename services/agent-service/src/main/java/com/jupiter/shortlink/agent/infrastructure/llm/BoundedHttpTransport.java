package com.jupiter.shortlink.agent.infrastructure.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * Finite shared I/O budget for business/analytics/Command calls, independent of the model
 * transport.
 */
@Component
public final class BoundedHttpTransport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Semaphore calls = new Semaphore(16);
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    public Map<String, Object> exchange(
            String method, URI uri, Map<String, String> headers, Object payload) {
        if (!calls.tryAcquire())
            throw new IllegalStateException("Agent business I/O concurrency budget exhausted");
        try {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
            headers.forEach(request::header);
            request.header("Accept", "application/json");
            if ("GET".equals(method)) request.GET();
            else {
                byte[] body = JSON.writeValueAsBytes(payload);
                if (body.length > 256 * 1024)
                    throw new IllegalArgumentException("Agent request exceeds byte budget");
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            }
            HttpResponse<byte[]> response =
                    client.send(request.build(), ignored -> new LimitedBody(2 * 1024 * 1024));
            if (response.statusCode() != 200)
                throw new HttpStatusFailure(response.statusCode());
            Map<String, Object> decoded = JSON.readValue(response.body(), new TypeReference<>() {});
            if (decoded == null)
                throw new IllegalStateException("Business authority returned an empty response");
            return decoded;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Business authority request interrupted", interrupted);
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException("Business authority is unavailable", unavailable);
        } finally {
            calls.release();
        }
    }

    /** Status remains machine readable without retaining an untrusted remote error body. */
    public static final class HttpStatusFailure extends IllegalStateException {
        private final int statusCode;

        public HttpStatusFailure(int statusCode) {
            super("Business authority rejected request with HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int statusCode() { return statusCode; }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedBody(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return future;
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            subscription = incoming;
            incoming.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    subscription.cancel();
                    future.completeExceptionally(
                            new IllegalStateException("Business response exceeded byte budget"));
                    return;
                }
                byte[] copy = new byte[chunk.remaining()];
                chunk.get(copy);
                bytes.writeBytes(copy);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            future.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            future.complete(bytes.toByteArray());
        }
    }
}
