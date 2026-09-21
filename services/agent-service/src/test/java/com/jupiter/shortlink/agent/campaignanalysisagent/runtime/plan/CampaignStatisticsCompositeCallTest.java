package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildMode;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildRecord;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.ChildState;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.DispatchPurpose;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.UnresolvedReason;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.WireRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CampaignStatisticsCompositeCallTest {
    private static final WireRequest GET = new WireRequest("GET", "/stats", "{}");

    @Test
    void preservesReadyAndWaitingFactsWhenAnIndependentChildFails() throws Exception {
        var composite = new CampaignStatisticsCompositeCall();
        var first = request("a", ChildMode.SYNC);
        var second = request("b", ChildMode.ASYNC);
        var third = request("c", ChildMode.SYNC);
        var calls = new AtomicInteger();
        var outcome = composite.execute(List.of(first, second, third), request -> {
            calls.incrementAndGet();
            return switch (request.childId()) {
                case "a" -> ready(request);
                case "b" -> waiting(request, "job-b");
                default -> throw new IllegalStateException("QUERY_FAILED");
            };
        });

        assertEquals(3, calls.get());
        assertEquals(List.of("a"), outcome.readyChildIds());
        assertEquals(List.of("b"), outcome.waitingChildIds());
        assertEquals(List.of("job-b"), outcome.waitingJobs());
        assertEquals(List.of(new CampaignStatisticsCompositeCall.Failure("c", "QUERY_FAILED")), outcome.failures());
        assertTrue(outcome.waiting());
        assertTrue(!outcome.complete());
    }

    @Test
    void rejectsDuplicateChildOrRequestIdentityBeforeAnyDispatch() {
        var composite = new CampaignStatisticsCompositeCall();
        var calls = new AtomicInteger();
        var duplicateChild = request("same", ChildMode.SYNC);
        assertEquals("COMPOSITE_CHILD_DUPLICATE", assertThrows(IllegalArgumentException.class,
                () -> composite.execute(List.of(duplicateChild, duplicateChild), ignored -> {
                    calls.incrementAndGet(); return ready(ignored);
                })).getMessage());
        var duplicateRequest = new CampaignStatisticsCompositeCall.Request(
                "different", "action", ChildMode.SYNC, "request-a", GET, boundary -> CampaignStepExecution.ChildResult.waiting("job"));
        var sameRequest = new CampaignStatisticsCompositeCall.Request(
                "other", "action", ChildMode.SYNC, "request-a", GET, boundary -> CampaignStepExecution.ChildResult.waiting("job"));
        assertEquals("COMPOSITE_REQUEST_DUPLICATE", assertThrows(IllegalArgumentException.class,
                () -> composite.execute(List.of(duplicateRequest, sameRequest), ignored -> {
                    calls.incrementAndGet(); return ready(ignored);
                })).getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    void securityFenceIsNeverDowngradedToAnOrdinaryChildFailure() {
        var composite = new CampaignStatisticsCompositeCall();
        assertThrows(SecurityException.class, () -> composite.execute(List.of(request("a", ChildMode.SYNC)), ignored -> {
            throw new SecurityException("STEP_EXECUTION_FENCED");
        }));
    }

    @Test
    void outcomeListsAreImmutableAndUnknownChildStateRemainsFailure() throws Exception {
        var composite = new CampaignStatisticsCompositeCall();
        var request = request("a", ChildMode.SYNC);
        var outcome = composite.execute(List.of(request), ignored -> new ChildRecord(request.spec(),
                ChildState.UNRESOLVED, null, null, "attempt", 1, DispatchPurpose.FRESH, false,
                UnresolvedReason.JOB_RESULT_UNKNOWN));
        assertEquals(List.of(new CampaignStatisticsCompositeCall.Failure("a", "JOB_RESULT_UNKNOWN")), outcome.failures());
        assertThrows(UnsupportedOperationException.class, () -> outcome.children().clear());
    }

    private static CampaignStatisticsCompositeCall.Request request(String id, ChildMode mode) {
        return new CampaignStatisticsCompositeCall.Request(id, "action", mode, "request-" + id, GET,
                boundary -> CampaignStepExecution.ChildResult.waiting("job-" + id));
    }

    private static ChildRecord ready(CampaignStatisticsCompositeCall.Request request) {
        return new ChildRecord(request.spec(), ChildState.READY, null, "artifact-" + request.childId(),
                "attempt", 1, DispatchPurpose.FRESH, false, null);
    }

    private static ChildRecord waiting(CampaignStatisticsCompositeCall.Request request, String job) {
        return new ChildRecord(request.spec(), ChildState.WAITING, job, null, "attempt", 1,
                DispatchPurpose.FRESH, false, null);
    }
}
