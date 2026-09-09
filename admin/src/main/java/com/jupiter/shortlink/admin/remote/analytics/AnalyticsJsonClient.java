package com.jupiter.shortlink.admin.remote.analytics;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.RemoteException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

@Component
public class AnalyticsJsonClient {
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final String commandUrl;
    private final String analyticsUrl;
    private final String internalToken;
    private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(32);

    public AnalyticsJsonClient(
            @Value("${shortlink.command.base-url:http://127.0.0.1:8001}") String commandUrl,
            @Value("${shortlink.analytics.base-url:http://127.0.0.1:8004}") String analyticsUrl,
            @Value("${shortlink.internal-token:}") String internalToken) {
        this.commandUrl = commandUrl.replaceAll("/+$", "");
        this.analyticsUrl = analyticsUrl.replaceAll("/+$", "");
        this.internalToken = internalToken;
    }

    public JSONObject resolve(Object request) {
        return post(commandUrl + "/internal/command/authorization/resolve", request);
    }

    public JSONObject query(Object request) {
        return post(analyticsUrl + "/internal/analytics/v1/query", request);
    }

    public JSONObject createJob(Object request) {
        return post(analyticsUrl + "/internal/analytics/v1/jobs", request);
    }

    public JSONObject job(String jobId, String operation, Object request) {
        if (jobId == null
                || !jobId.matches("[A-Za-z0-9_-]{1,128}")
                || !List.of("status", "page").contains(operation))
            throw new IllegalArgumentException("Invalid statistics job reference");
        return post(
                analyticsUrl + "/internal/analytics/v1/jobs/" + jobId + "/" + operation, request);
    }

    private JSONObject post(String url, Object payload) {
        if (internalToken == null
                || internalToken.length() < 24
                || UserContext.getUserId() == null
                || UserContext.getUsername() == null
                || UserContext.getAuthVersion() == null) {
            throw new RemoteException("Trusted analytics service identity is unavailable");
        }
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Token", internalToken)
                        .header("x-shortlink-tenant-id", UserContext.getUserId())
                        .header("x-shortlink-username", UserContext.getUsername())
                        .header(
                                "x-shortlink-auth-version",
                                Long.toString(UserContext.getAuthVersion()))
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(payload)))
                        .build();
        if (!permits.tryAcquire())
            throw new RemoteException("Analytics request concurrency budget is exhausted");
        try {
            HttpResponse<byte[]> response =
                    client.send(request, ignored -> new LimitedBody(2 * 1024 * 1024));
            if (response.statusCode() != 200)
                throw new RemoteException(
                        "Analytics authority returned HTTP " + response.statusCode());
            JSONObject result =
                    JSON.parseObject(
                            new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
            if (result == null)
                throw new RemoteException("Analytics authority returned an empty response");
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RemoteException("Analytics request interrupted");
        } catch (java.io.IOException | IllegalArgumentException unavailable) {
            throw new RemoteException("Analytics authority is unavailable");
        } finally {
            permits.release();
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> future = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBody(int limit) {
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
                            new IllegalStateException("Analytics response exceeds budget"));
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
