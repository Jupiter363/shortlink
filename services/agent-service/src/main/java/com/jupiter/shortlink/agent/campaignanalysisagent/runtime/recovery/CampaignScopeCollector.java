package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.*;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore.Collection;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore.Definition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignScopeStore.State;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.contract.GroupMembersPage;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable authority collection pass; no scheduler, model loop or Spring/runtime registration. */
public final class CampaignScopeCollector {
    public enum Outcome { PROGRESS, READY, BLOCKED, STOPPED }
    public record Result(Outcome outcome, String code, Collection collection) {}
    @FunctionalInterface
    public interface PageReader {
        GroupMembersPage read(AgentPrincipal principal, String gid, Long afterLinkId, String ownershipVersion);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CampaignRunStore runs;
    private final CampaignScopeStore scopes;
    private final PageReader authority;
    private final int pagesPerPass;

    public CampaignScopeCollector(CampaignRunStore runs, CampaignScopeStore scopes, AgentAuthorityClient authority) {
        this(runs, scopes, authority::resolveGroupMembersPage, 8);
    }

    /** Work quantum only: later passes resume the durable cursor, with no total membership cap. */
    public CampaignScopeCollector(CampaignRunStore runs, CampaignScopeStore scopes, PageReader authority, int pagesPerPass) {
        this.runs = Objects.requireNonNull(runs);
        this.scopes = Objects.requireNonNull(scopes);
        this.authority = Objects.requireNonNull(authority);
        if (pagesPerPass < 1) throw new IllegalArgumentException("Positive authority page quantum required");
        this.pagesPerPass = pagesPerPass;
    }

    public Result collect(RunToken token, Definition definition, AgentPrincipal current, BooleanSupplier authorized) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("SCOPE_IO_REQUIRES_COMMITTED_PREPARATION");
        requirePrincipal(token, current);
        Objects.requireNonNull(authorized);
        if (!authorized.getAsBoolean()) return stopped(null);
        Collection collection = scopes.prepare(token, definition);
        for (int received = 0; received < pagesPerPass; received++) {
            if (!current(token, authorized)) return stopped(collection);
            if (collection.state() == State.PUBLISHED) return new Result(Outcome.READY, null, collection);
            if (collection.state() == State.INVALID) return new Result(Outcome.BLOCKED, collection.failureCode(), collection);
            var request = new GroupMembersPage.Request(definition.gid(), collection.nextCursor(), collection.enumerationVersion());
            Caller owner = token.definition().caller();
            String identity = CampaignRunStore.sha256(json(List.of("scope-page-slot/v1", owner.tenantId(),
                    owner.subject(), owner.authVersion(), token.definition().runId(), token.definition().revision(),
                    definition.action().actionId(), definition.collectionId(), collection.pageCount())));
            var wire = new WireRequest("POST", AgentAuthorityClient.GROUP_MEMBERS_PATH, json(request.asMap()));
            ChildRecord child = runs.prepareChild(token, new ChildSpec("scope-page-" + identity,
                    definition.action().actionId(), ChildMode.SYNC, "scope-read-" + identity, wire));
            if (child.callbackActive() || child.state() == ChildState.DISPATCHING)
                return new Result(Outcome.BLOCKED, "EXECUTION_UNRESOLVED", collection);
            if (child.state() == ChildState.UNRESOLVED && request.afterLinkId() == null)
                return new Result(Outcome.BLOCKED, "READ_RESULT_UNKNOWN", collection);
            if (child.state() != ChildState.PREPARED && child.state() != ChildState.UNRESOLVED)
                return new Result(Outcome.BLOCKED, "SCOPE_PAGE_STATE_INVALID", collection);
            DispatchPermit permit = child.state() == ChildState.UNRESOLVED
                    ? runs.beginAuthorityPageReconciliation(token, child.spec().childId())
                    : runs.beginDispatch(token, child.spec().childId());
            try {
                if (!live(permit, authorized)) return stopped(collection);
                GroupMembersPage page = authority.read(current, request.gid(), request.afterLinkId(), request.ownershipVersion());
                if (!live(permit, authorized)) return stopped(collection);
                if (page == null) throw new IllegalArgumentException("AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE");
                page.requireMatches(request, current.tenantId(), current.username(), current.authVersion());
                if (!live(permit, authorized)) return stopped(collection);
                collection = scopes.commitPage(permit, definition.collectionId(), page);
                if (collection.state() == State.INVALID)
                    return new Result(Outcome.BLOCKED, collection.failureCode(), collection);
            } catch (IllegalArgumentException | IllegalStateException | SecurityException rejected) {
                if (!live(permit, authorized)) return stopped(collection);
                String code = rejected instanceof AgentAuthorityClient.AuthorityPageException failure
                        ? failure.code() : safeCode(rejected.getMessage());
                if ("QUERY_SCOPE_CHANGED".equals(code) || "FORBIDDEN".equals(code))
                    collection = scopes.invalidate(token, definition.collectionId(), code);
                return new Result(Outcome.BLOCKED, code, collection);
            } finally {
                try {
                    try { if (runs.mayDispatch(permit)) runs.markUnresolved(permit); }
                    catch (IllegalStateException fenced) { if (runs.mayDispatch(permit)) throw fenced; }
                } finally {
                    // This is the real synchronous callback exit, never a timeout/death inference.
                    runs.callbackExited(permit);
                }
            }
        }
        if (!current(token, authorized)) return stopped(collection);
        return new Result(collection.state() == State.PUBLISHED ? Outcome.READY : Outcome.PROGRESS, null, collection);
    }

    private boolean current(RunToken token, BooleanSupplier authorized) {
        if (!authorized.getAsBoolean()) return false;
        return runs.loadRun(token.definition().caller(), token.definition().runId())
                .filter(run -> run.status() == RunStatus.ACTIVE && token.equals(run.token())).isPresent();
    }

    private boolean live(DispatchPermit permit, BooleanSupplier authorized) {
        return authorized.getAsBoolean() && runs.mayDispatch(permit);
    }

    private static Result stopped(Collection collection) {
        return new Result(Outcome.STOPPED, "SCOPE_AUTHORITY_REVOKED", collection);
    }

    private static String safeCode(String code) {
        return code != null && code.matches("[A-Z][A-Z0-9_]{0,63}") ? code : "AUTHORITY_PAGE_PROTOCOL_UNAVAILABLE";
    }

    private static void requirePrincipal(RunToken token, AgentPrincipal current) {
        Caller owner = token.definition().caller();
        if (current == null || current.system() || !owner.tenantId().equals(current.tenantId())
                || !owner.subject().equals(current.username()) || owner.authVersion() != current.authVersion())
            throw new SecurityException("SCOPE_PRINCIPAL_MISMATCH");
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException impossible) { throw new IllegalArgumentException("SCOPE_REQUEST_INVALID", impossible); }
    }
}
