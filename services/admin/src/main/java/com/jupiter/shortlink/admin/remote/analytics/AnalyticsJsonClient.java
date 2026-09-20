package com.jupiter.shortlink.admin.remote.analytics;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.RemoteException;
import com.jupiter.shortlink.admin.common.convention.errorcode.IErrorCode;

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
    private enum ResponseContract { DEFAULT, RECOVERY, JOB_READ, JOB_SCOPE_READ, FROZEN_JOB, SELECTED_SCOPE, JOB_RELEASE, JOB_CANCEL, AUTHORITY_PAGE }

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

    /** Group-only authority pagination; old response shapes never fall back to ordinary resolve. */
    public JSONObject groupMembersPage(Object request) {
        return post(commandUrl + "/internal/command/authorization/resolve", request, ResponseContract.AUTHORITY_PAGE);
    }

    /** Reauthorize a job read with typed failures; ordinary resource resolution is unchanged. */
    public JSONObject resolveForJobRead(Object request) {
        return post(commandUrl + "/internal/command/authorization/resolve", request,
                ResponseContract.JOB_SCOPE_READ);
    }

    public JSONObject query(Object request) {
        return post(analyticsUrl + "/internal/analytics/v1/query", request);
    }

    public JSONObject createJob(Object request) {
        return post(analyticsUrl + "/internal/analytics/v1/jobs", request);
    }

    /** Dedicated route: no retry or fallback to createJob, including unsupported old services. */
    public JSONObject recoverExistingJob(Object request) {
        return post(analyticsUrl + "/internal/analytics/v1/jobs/recover-existing", request,
                ResponseContract.RECOVERY);
    }

    /** Explicit selected members only; an unavailable authority never falls back to group resolution. */
    public JSONObject resolveSelected(Object request) {
        return post(commandUrl + "/internal/command/authorization/resolve-selected", request,
                ResponseContract.SELECTED_SCOPE);
    }

    public JSONObject createFrozenJob(Object request) {
        return post(analyticsUrl + "/internal/analytics/v1/jobs/frozen", request, ResponseContract.FROZEN_JOB);
    }

    public JSONObject recoverFrozenJob(Object request) {
        return post(analyticsUrl + "/internal/analytics/v1/jobs/frozen/recover-existing", request,
                ResponseContract.FROZEN_JOB);
    }

    /** Release an original frozen job only; old services must not be retried through another route. */
    public JSONObject releaseFrozenJobResult(String jobId, Object request) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,128}")) throw releaseFailure("INVALID_QUERY");
        return post(analyticsUrl + "/internal/analytics/v1/jobs/" + jobId + "/release-result", request,
                ResponseContract.JOB_RELEASE);
    }

    /** One mutation attempt only. Lost acknowledgements are reconciled through the status route. */
    public JSONObject cancelJob(String jobId, Object identity) {
        if (jobId == null || !jobId.matches("[A-Za-z0-9_-]{1,128}")) throw cancelFailure("INVALID_QUERY");
        return post(analyticsUrl + "/internal/analytics/v1/jobs/" + jobId + "/cancel", identity,
                ResponseContract.JOB_CANCEL);
    }

    public JSONObject job(String jobId, String operation, Object request) {
        if (jobId == null
                || !jobId.matches("[A-Za-z0-9_-]{1,128}")
                || !List.of("status", "page").contains(operation))
            throw readFailure("INVALID_QUERY");
        return post(
                analyticsUrl + "/internal/analytics/v1/jobs/" + jobId + "/" + operation, request,
                ResponseContract.JOB_READ);
    }

    private JSONObject post(String url, Object payload) {
        return post(url, payload, ResponseContract.DEFAULT);
    }

    private JSONObject post(String url, Object payload, ResponseContract contract) {
        boolean recovery = contract == ResponseContract.RECOVERY;
        boolean authorityPage = contract == ResponseContract.AUTHORITY_PAGE;
        boolean release = contract == ResponseContract.JOB_RELEASE;
        boolean cancel = contract == ResponseContract.JOB_CANCEL;
        boolean frozen = contract == ResponseContract.FROZEN_JOB || contract == ResponseContract.SELECTED_SCOPE;
        boolean jobRead = contract == ResponseContract.JOB_READ
                || contract == ResponseContract.JOB_SCOPE_READ;
        if (internalToken == null
                || internalToken.length() < 24
                || UserContext.getUserId() == null
                || UserContext.getUsername() == null
                || UserContext.getAuthVersion() == null) {
            if (authorityPage) throw authorityPageFailure("FORBIDDEN");
            if (release) throw releaseFailure("FORBIDDEN");
            if (cancel) throw cancelFailure("FORBIDDEN");
            if (frozen) throw frozenFailure("FORBIDDEN");
            if (jobRead) throw readFailure("FORBIDDEN");
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
        if (!permits.tryAcquire()) {
            if (authorityPage) throw authorityPageFailure("REMOTE_UNAVAILABLE");
            if (release) throw releaseFailure("REMOTE_UNAVAILABLE");
            if (cancel) throw cancelFailure("REMOTE_UNAVAILABLE");
            if (frozen) throw frozenFailure("REMOTE_UNAVAILABLE");
            if (jobRead) throw readFailure("REMOTE_UNAVAILABLE");
            throw new RemoteException("Analytics request concurrency budget is exhausted");
        }
        try {
            HttpResponse<byte[]> response =
                    client.send(request, ignored -> new LimitedBody(2 * 1024 * 1024));
            if (authorityPage) return authorityPageResponse(response);
            if (release) return releaseResponse(response);
            if (cancel) return cancelResponse(response);
            if (frozen) return frozenResponse(response, contract == ResponseContract.SELECTED_SCOPE);
            if (jobRead) return readResponse(response, contract == ResponseContract.JOB_SCOPE_READ);
            if (recovery && List.of(404, 405, 501).contains(response.statusCode())) {
                throw recoveryFailure("RECOVERY_PROTOCOL_UNAVAILABLE");
            }
            if (recovery) return recoveryResponse(response);
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
            if (authorityPage) throw authorityPageFailure("REMOTE_UNAVAILABLE");
            if (release) throw releaseFailure("REMOTE_UNAVAILABLE");
            if (cancel) throw cancelFailure("REMOTE_UNAVAILABLE");
            if (frozen) throw frozenFailure("REMOTE_UNAVAILABLE");
            if (jobRead) throw readFailure("REMOTE_UNAVAILABLE");
            if (recovery) throw recoveryFailure("UNAVAILABLE");
            throw new RemoteException("Analytics request interrupted");
        } catch (java.io.IOException | IllegalArgumentException unavailable) {
            if (authorityPage) throw authorityPageFailure("REMOTE_UNAVAILABLE");
            if (release) throw releaseFailure("REMOTE_UNAVAILABLE");
            if (cancel) throw cancelFailure("REMOTE_UNAVAILABLE");
            if (frozen) throw frozenFailure("REMOTE_UNAVAILABLE");
            if (jobRead) throw readFailure("REMOTE_UNAVAILABLE");
            if (recovery) throw recoveryFailure("UNAVAILABLE");
            throw new RemoteException("Analytics authority is unavailable");
        } finally {
            permits.release();
        }
    }

    private static JSONObject authorityPageResponse(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) throw authorityPageFailure("FORBIDDEN");
        if (status == 409) throw authorityPageFailure("QUERY_SCOPE_CHANGED");
        if (status == 404 || status == 405 || status == 501)
            throw authorityPageFailure("AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE");
        if (status != 200) throw authorityPageFailure("REMOTE_UNAVAILABLE");
        JSONObject result;
        try { result = JSON.parseObject(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw authorityPageFailure("AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE"); }
        if (result == null || !java.util.Set.of("tenantId", "ownershipVersion", "links", "nextCursor").equals(result.keySet()))
            throw authorityPageFailure("AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE");
        return result;
    }

    public static RemoteException authorityPageFailure(String reason) {
        String code = reason != null && List.of("FORBIDDEN", "QUERY_SCOPE_CHANGED", "INVALID_QUERY", "REMOTE_UNAVAILABLE")
                .contains(reason) ? reason : "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE";
        return new RemoteException("Group member authority unavailable", errorCode(code, "Group member authority unavailable"));
    }

    private static JSONObject frozenResponse(HttpResponse<byte[]> response, boolean selectedScope) {
        int status = response.statusCode();
        if (status == 401 || status == 403) throw frozenFailure("FORBIDDEN");
        if (status == 400) throw frozenFailure("INVALID_QUERY");
        if (status == 409) throw frozenFailure("QUERY_SCOPE_CHANGED");
        if (status == 404 || status == 405 || status == 501)
            throw frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        if (status != 200) throw frozenFailure("REMOTE_UNAVAILABLE");
        JSONObject result;
        try {
            result = JSON.parseObject(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            throw frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        }
        if (result == null) throw frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        // Command is an unwrapped, versioned authority response, not an Analytics result envelope.
        if (selectedScope && !result.containsKey("code")) return result;
        if (!(result.get("code") instanceof String code))
            throw frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        if (!selectedScope) rejectCapacity(result);
        if (!"0".equals(code)) throw frozenFailure(code);
        if (selectedScope || result.containsKey("success") && !Boolean.TRUE.equals(result.get("success")))
            throw frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        return result;
    }

    private static JSONObject cancelResponse(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) throw cancelFailure("FORBIDDEN");
        if (status == 400) throw cancelFailure("INVALID_QUERY");
        if (status == 409) throw cancelFailure("CONFLICT");
        if (status == 404 || status == 405 || status == 501)
            throw cancelFailure("STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE");
        if (status != 200) throw cancelFailure("REMOTE_UNAVAILABLE");
        JSONObject result;
        try { result = JSON.parseObject(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw cancelFailure("STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE"); }
        if (result == null || !(result.get("code") instanceof String code))
            throw cancelFailure("STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE");
        if (!"0".equals(code)) throw cancelFailure(code);
        if (result.containsKey("success") && !Boolean.TRUE.equals(result.get("success")))
            throw cancelFailure("STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE");
        return result;
    }

    static RemoteException cancelFailure(String upstreamCode) {
        String code = upstreamCode != null && upstreamCode.matches("[A-Z][A-Z0-9_]{0,63}")
                ? upstreamCode : "STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE";
        return new RemoteException("Statistics job cancellation unavailable", errorCode(code, "Statistics job cancellation unavailable"));
    }

    private static JSONObject releaseResponse(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 401 || status == 403) throw releaseFailure("FORBIDDEN");
        if (status == 400) throw releaseFailure("INVALID_QUERY");
        if (status == 409) throw releaseFailure("CONFLICT");
        if (status == 404 || status == 405 || status == 501)
            throw releaseFailure("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE");
        if (status != 200) throw releaseFailure("REMOTE_UNAVAILABLE");
        JSONObject result;
        try { result = JSON.parseObject(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw releaseFailure("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE"); }
        if (result == null || !(result.get("code") instanceof String code))
            throw releaseFailure("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE");
        if (!"0".equals(code)) throw releaseFailure(code);
        if (result.containsKey("success") && !Boolean.TRUE.equals(result.get("success")))
            throw releaseFailure("STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE");
        return result;
    }

    static RemoteException releaseFailure(String upstreamCode) {
        String code = upstreamCode != null && upstreamCode.matches("[A-Z][A-Z0-9_]{0,63}")
                ? upstreamCode : "STATISTICS_RELEASE_PROTOCOL_UNAVAILABLE";
        return new RemoteException("Statistics result release unavailable", errorCode(code, "Statistics result release unavailable"));
    }

    /** Only an explicit, typed non-admission receipt may cross the Admin boundary as retryable capacity. */
    static void rejectCapacity(JSONObject response) {
        if (response == null || !"QUERY_CAPACITY_EXHAUSTED".equals(response.get("code"))) return;
        if (!Boolean.FALSE.equals(response.get("admitted"))
                || response.containsKey("success") && !Boolean.FALSE.equals(response.get("success"))
                || response.get("data") != null
                || !(response.get("capacityKind") instanceof String kind)
                || !List.of("ACTIVE_EXECUTION", "RESULT_STORAGE", "RECOVERY_IDENTITY").contains(kind))
            throw frozenFailure("FROZEN_SCOPE_PROTOCOL_UNAVAILABLE");
        throw new StatisticsCapacityException(kind);
    }

    public static final class StatisticsCapacityException extends RemoteException {
        private final String capacityKind;
        private StatisticsCapacityException(String kind) {
            super("Statistics query capacity exhausted", errorCode("QUERY_CAPACITY_EXHAUSTED", "Statistics query capacity exhausted"));
            capacityKind = kind;
        }
        public String capacityKind() { return capacityKind; }
    }

    private static IErrorCode errorCode(String code, String message) {
        return new IErrorCode() {
            @Override public String code() { return code; }
            @Override public String message() { return message; }
        };
    }

    static RemoteException frozenFailure(String upstreamCode) {
        String code = upstreamCode != null && upstreamCode.matches("[A-Z][A-Z0-9_]{0,63}")
                ? upstreamCode : "FROZEN_SCOPE_PROTOCOL_UNAVAILABLE";
        return new RemoteException("Frozen statistics scope unavailable", new IErrorCode() {
            @Override public String code() { return code; }
            @Override public String message() { return "Frozen statistics scope unavailable"; }
        });
    }

    private static JSONObject readResponse(HttpResponse<byte[]> response, boolean scopeResponse) {
        int status = response.statusCode();
        if (status == 401 || status == 403) throw readFailure("FORBIDDEN");
        if (status == 404 || status == 405 || status == 501)
            throw readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        if (status != 200) throw readFailure("REMOTE_UNAVAILABLE");
        JSONObject result;
        try {
            result = JSON.parseObject(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            throw readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        }
        if (result == null) throw readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        // Command authorization returns an unwrapped scope, whereas Analytics returns code/data.
        if (scopeResponse && status == 200 && !result.containsKey("code")) return result;
        if (!(result.get("code") instanceof String code))
            throw readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        if (!"0".equals(code)) throw readFailure(code);
        if (status != 200 || scopeResponse)
            throw readFailure("STATISTICS_READ_PROTOCOL_UNAVAILABLE");
        return result;
    }

    static RemoteException readFailure(String upstreamCode) {
        String code = upstreamCode != null && upstreamCode.matches("[A-Z][A-Z0-9_]{0,63}")
                ? upstreamCode : "STATISTICS_READ_PROTOCOL_UNAVAILABLE";
        return new RemoteException("Statistics job read unavailable", new IErrorCode() {
            @Override public String code() { return code; }
            @Override public String message() { return "Statistics job read unavailable"; }
        });
    }

    private static JSONObject recoveryResponse(HttpResponse<byte[]> response) {
        JSONObject result;
        try {
            result = JSON.parseObject(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            throw recoveryFailure("RECOVERY_PROTOCOL_UNAVAILABLE");
        }
        if (result == null || result.getString("code") == null) {
            throw recoveryFailure("RECOVERY_PROTOCOL_UNAVAILABLE");
        }
        String code = result.getString("code");
        if (!"0".equals(code)) throw recoveryFailure(code);
        if (response.statusCode() != 200) throw recoveryFailure("RECOVERY_PROTOCOL_UNAVAILABLE");
        return result;
    }

    static RemoteException recoveryFailure(String upstreamCode) {
        // Preserve only a bounded machine identifier. SQL, response bodies and upstream messages
        // never enter the user-visible error message; the existing exception handler keeps code.
        String code = upstreamCode != null && upstreamCode.matches("[A-Z][A-Z0-9_]{0,63}")
                ? upstreamCode : "RECOVERY_PROTOCOL_UNAVAILABLE";
        return new RemoteException("Statistics job recovery unavailable", new IErrorCode() {
            @Override public String code() { return code; }
            @Override public String message() { return "Statistics job recovery unavailable"; }
        });
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
