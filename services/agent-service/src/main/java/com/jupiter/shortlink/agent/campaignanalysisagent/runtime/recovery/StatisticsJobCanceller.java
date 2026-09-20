package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.jupiter.shortlink.agent.business.shortlink.ShortLinkBusinessGateway;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.capacity.ProcessExecutionScope;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsCancellationStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsCancellationStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignStatisticsConsumerStore.Binding;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import java.math.BigInteger;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One admitted cancellation pass. A lost acknowledgement is reconciled only by reading the original job. */
public final class StatisticsJobCanceller {
    public enum Outcome { NOT_NEEDED, PENDING, TERMINAL, BLOCKED, STOPPED }
    public record Result(String bindingId, String jobId, Outcome outcome, String observedState, String code) {}

    @FunctionalInterface public interface Authorizer {
        /** Recheck the current principal's grant against this exact stored query; never authorize by jobId alone. */
        boolean mayCancel(RunToken current, Binding binding, Map<String, Object> originalRequest);
    }

    private static final Set<String> STATES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final Set<String> FAILURE_CODES = Set.of("FORBIDDEN", "REMOTE_UNAVAILABLE",
            "STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE", "STATISTICS_READ_PROTOCOL_UNAVAILABLE");
    private final CampaignStatisticsCancellationStore operations;
    private final ShortLinkBusinessGateway gateway;
    private final Clock clock;

    public StatisticsJobCanceller(CampaignStatisticsCancellationStore operations, ShortLinkBusinessGateway gateway, Clock clock) {
        this.operations = Objects.requireNonNull(operations);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result cancel(RunToken token, String bindingId, AgentPrincipal current,
                         Authorizer authorizer, ProcessExecutionScope scope) {
        Objects.requireNonNull(token); Objects.requireNonNull(bindingId); Objects.requireNonNull(authorizer);
        Objects.requireNonNull(scope);
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("STATISTICS_CANCEL_AMBIENT_TRANSACTION");
        requirePrincipal(token, current);
        try (var ignored = scope.enter()) {
            ReadAccess access = operations.readAccess(token, bindingId);
            Map<String, Object> request = originalRequest(token, access);
            if (!authorized(authorizer, token, access.binding(), request)) return stopped(bindingId, access.binding().jobId());
            var requested = operations.request(token, bindingId);
            if (requested.isEmpty()) return new Result(bindingId, access.binding().jobId(), Outcome.NOT_NEEDED, null, null);
            Operation operation = requested.orElseThrow();
            if (operation.state() == State.TERMINAL)
                return new Result(bindingId, access.binding().jobId(), Outcome.TERMINAL, operation.observedState(), operation.reasonCode());
            if (operation.callbackActive())
                return new Result(bindingId, access.binding().jobId(), Outcome.BLOCKED, null, "STATISTICS_CANCEL_CALLBACK_ACTIVE");
            access = operations.readAccess(token, bindingId);
            request = originalRequest(token, access);
            if (!authorized(authorizer, token, access.binding(), request)) return stopped(bindingId, access.binding().jobId());
            Permit permit = operations.begin(token, bindingId);
            Binding binding = access.binding();
            boolean recorded = false;
            try {
                if (!live(permit, authorizer, binding, request)) return stopped(bindingId, binding.jobId());
                var context = new ToolContext(token.definition().sessionId(), current.username(), request, current);
                ToolResult response = invoke(permit, context, binding.jobId());
                if (!live(permit, authorizer, binding, request)) return stopped(bindingId, binding.jobId());
                if (response == null || !response.success())
                    return new Result(bindingId, binding.jobId(), Outcome.PENDING, null, failureCode(response));
                var status = status(response.data(), binding, permit.purpose());
                if (!live(permit, authorizer, binding, request)) return stopped(bindingId, binding.jobId());
                Operation stored = operations.recordStatus(permit, status);
                recorded = true;
                return new Result(bindingId, binding.jobId(), stored.state() == State.TERMINAL ? Outcome.TERMINAL : Outcome.PENDING,
                        stored.observedState(), stored.reasonCode());
            } catch (IllegalArgumentException invalid) {
                return new Result(bindingId, binding.jobId(), Outcome.BLOCKED, null, "STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE");
            } catch (IllegalStateException | SecurityException fenced) {
                return new Result(bindingId, binding.jobId(), Outcome.STOPPED, null, "STATISTICS_CANCEL_AUTHORITY_REVOKED");
            } finally {
                try { if (!recorded) operations.markUnknown(permit); }
                finally { operations.callbackExited(permit); }
            }
        }
    }

    private Map<String, Object> originalRequest(RunToken token, ReadAccess access) {
        Binding binding = access.binding(); ChildRecord source = access.sourceChild();
        if (!token.definition().caller().equals(binding.owner()) || !token.definition().runId().equals(binding.producerRunId())
                || binding.expiresAtMillis() <= clock.millis() || source.spec().mode() != ChildMode.ASYNC
                || source.spec().wire() == null || !binding.producerChildId().equals(source.spec().childId())
                || !binding.actionId().equals(source.spec().actionId()) || !binding.jobId().equals(source.jobId())
                || !binding.requestId().equals(source.spec().requestId()) || !binding.requestHash().equals(source.spec().wire().hash()))
            throw new SecurityException("STATISTICS_CANCEL_BINDING_INVALID");
        return new StatisticsJobResultProtocol(source).request();
    }

    private boolean live(Permit permit, Authorizer authorizer, Binding binding, Map<String, Object> request) {
        return binding.expiresAtMillis() > clock.millis() && operations.mayDispatch(permit)
                && authorized(authorizer, permit.token(), binding, request);
    }
    private static boolean authorized(Authorizer authorizer, RunToken token, Binding binding, Map<String, Object> request) {
        try { return authorizer.mayCancel(token, binding, request); }
        catch (RuntimeException denied) { return false; }
    }
    private ToolResult invoke(Permit permit, ToolContext context, String jobId) {
        try {
            return permit.purpose() == Purpose.CANCEL
                    ? gateway.cancelStatisticsJob(context, jobId) : gateway.readStatisticsJob(context, jobId);
        } catch (RuntimeException unavailable) { return new ToolResult(false, Map.of("code", "REMOTE_UNAVAILABLE"), null); }
    }

    /** Cancel receipts intentionally have no row counts. Validate only the real public status contract. */
    private StatisticsJobResultProtocol.Status status(Object value, Binding binding, Purpose purpose) {
        if (!(value instanceof Map<?, ?> data) || !binding.jobId().equals(data.get("jobId"))
                || !(data.get("state") instanceof String state) || !STATES.contains(state)
                || purpose == Purpose.CANCEL && !TERMINAL.contains(state)
                || !data.containsKey("expiresAt") || !data.containsKey("resultState")
                || !data.containsKey("resultReady") || !data.containsKey("resultCode")) throw protocol();
        long expires = integer(data.get("expiresAt"));
        if (expires <= clock.millis() || expires != binding.expiresAtMillis()) throw protocol();
        boolean released = "RELEASED".equals(data.get("resultState"));
        if (released) {
            if (!TERMINAL.contains(state) || !Boolean.FALSE.equals(data.get("resultReady"))
                    || !"RESULT_RELEASED".equals(data.get("resultCode"))) throw protocol();
        } else {
            String expected = "SUCCEEDED".equals(state) ? "AVAILABLE" : TERMINAL.contains(state) ? "UNAVAILABLE" : "PENDING";
            if (!expected.equals(data.get("resultState")) || !Boolean.valueOf("SUCCEEDED".equals(state)).equals(data.get("resultReady"))
                    || data.get("resultCode") != null) throw protocol();
        }
        return new StatisticsJobResultProtocol.Status(binding.jobId(), state, 0, 0, expires, null);
    }
    private static long integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return ((Number) value).longValue();
        if (value instanceof BigInteger integer) {
            try { return integer.longValueExact(); } catch (ArithmeticException invalid) { throw protocol(); }
        }
        throw protocol();
    }
    private static IllegalArgumentException protocol() { return new IllegalArgumentException("STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE"); }
    private static String failureCode(ToolResult response) {
        if (response != null && response.data() instanceof Map<?, ?> data && data.get("code") instanceof String code
                && FAILURE_CODES.contains(code)) return code;
        return "STATISTICS_CANCEL_PROTOCOL_UNAVAILABLE";
    }
    private static Result stopped(String binding, String job) {
        return new Result(binding, job, Outcome.STOPPED, null, "STATISTICS_CANCEL_AUTHORITY_REVOKED");
    }
    private static void requirePrincipal(RunToken token, AgentPrincipal principal) {
        Caller owner = token.definition().caller();
        if (principal == null || principal.system() || !owner.tenantId().equals(principal.tenantId())
                || !owner.subject().equals(principal.username()) || owner.authVersion() != principal.authVersion())
            throw new SecurityException("STATISTICS_CANCEL_PRINCIPAL_MISMATCH");
    }
}
