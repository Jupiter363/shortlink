package com.jupiter.shortlink.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.exception.ClientException;
import com.jupiter.shortlink.admin.common.convention.result.*;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.dao.entity.GroupDO;
import com.jupiter.shortlink.admin.remote.*;
import com.jupiter.shortlink.admin.remote.dto.req.*;
import com.jupiter.shortlink.admin.remote.dto.resp.*;
import com.jupiter.shortlink.admin.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class RiskCenterFacadeServiceImpl implements RiskCenterFacadeService {
    private final AgentRiskRemoteService agentRiskRemoteService;
    private final AgentAdminConfiguration agentAdminConfiguration;
    private final GroupService groupService;
    private final CommandRiskRemoteService commands;

    @Override
    public Result<RiskGroupOverviewRespDTO> groupOverview(String gid) {
        TrustedUser user = trustedUser();
        requireOwnedGid(gid);
        var result =
                agentRiskRemoteService.groupOverview(
                        internalToken(), user.username(), user.userId(), user.authVersion(), gid);
        if (result != null && result.isSuccess() && result.getData() != null) {
            requireReturnedGid(gid, result.getData().getGid());
            enrich(result.getData().getTopRiskShortLinks(), gid, user);
            // A top-card page cannot establish the current disabled count for the whole group.
            result.getData().setDisabledCount(null);
            result.getData().setCurrentPolicyCoverage("TOP_CARDS_ONLY");
        }
        return result;
    }

    @Override
    public Result<List<RiskShortLinkCardRespDTO>> groupShortLinks(String gid) {
        TrustedUser user = trustedUser();
        requireOwnedGid(gid);
        var result =
                agentRiskRemoteService.groupShortLinks(
                        internalToken(), user.username(), user.userId(), user.authVersion(), gid);
        if (result != null && result.isSuccess()) enrich(result.getData(), gid, user);
        return result;
    }

    @Override
    public Result<RiskShortLinkDetailRespDTO> shortLinkDetail(
            String gid, String domain, String shortUri) {
        TrustedUser user = trustedUser();
        requireOwnedGid(gid);
        var result =
                agentRiskRemoteService.shortLinkDetail(
                        internalToken(),
                        user.username(),
                        user.userId(),
                        user.authVersion(),
                        gid,
                        domain,
                        shortUri);
        if (result != null && result.isSuccess()) {
            if (result.getData() == null || result.getData().getCard() == null)
                throw new ClientException("Risk center resource is unavailable");
            enrich(List.of(result.getData().getCard()), gid, user);
            Map<String, Object> history = result.getData().getLatestSnapshot();
            if (history != null) {
                history = new LinkedHashMap<>(history);
                history.remove("policyStatus");
                history.put("policyStatusSource", "HISTORICAL_ONLY");
                result.getData().setLatestSnapshot(history);
            }
        }
        return result;
    }

    @Override
    public Result<RiskPageRespDTO<?>> events(
            String gid,
            String targetType,
            String domain,
            String shortUri,
            Integer pageNo,
            Integer pageSize) {
        TrustedUser user = trustedUser();
        requireOwnedGid(gid);
        return agentRiskRemoteService.events(
                internalToken(),
                user.username(),
                user.userId(),
                user.authVersion(),
                gid,
                targetType,
                domain,
                shortUri,
                pageNo,
                pageSize);
    }

    @Override
    public Result<RiskReviewRespDTO> review(RiskReviewReqDTO request) {
        TrustedUser user = trustedUser();
        if (request == null) throw new ClientException("Review target is required");
        requireOwnedGid(request.getGid());
        request.setReviewer(user.username());
        // WATCH/FALSE_POSITIVE/CONFIRM_RISK are local review records; they never implicitly revoke
        // a policy.
        return agentRiskRemoteService.review(
                internalToken(), user.username(), user.userId(), user.authVersion(), request);
    }

    @Override
    public Result<Map<String, Object>> disablePolicy(
            String policyId, RiskPolicyDisableReqDTO request) {
        trustedUser();
        if (request == null) throw new ClientException("Revoke target is required");
        if (request.getLinkId() == null
                || request.getLinkId() < 1
                || request.getCommandId() == null
                || !request.getCommandId().matches("[A-Za-z0-9:_-]{8,128}")
                || policyId == null
                || policyId.isBlank())
            throw new ClientException("Stable commandId, linkId and policyId are required");
        try {
            // Explicit human revocation is authorized by Command and never depends on historical
            // statistics quality.
            return Results.success(
                    receipt(
                            commands.revoke(
                                    new CommandRiskRemoteService.Revoke(
                                            request.getCommandId(),
                                            request.getLinkId(),
                                            policyId))));
        } catch (feign.RetryableException unknown) {
            return Results.success(pending(request.getCommandId(), policyId));
        } catch (feign.FeignException failure) {
            if (failure.status() >= 500)
                return Results.success(pending(request.getCommandId(), policyId));
            throw failure;
        }
    }

    @Override
    public Result<Map<String, Object>> commandResult(String commandId) {
        trustedUser();
        if (commandId == null || !commandId.matches("[A-Za-z0-9:_-]{8,128}"))
            throw new ClientException("Stable commandId is required");
        try {
            return Results.success(receipt(commands.result(commandId)));
        } catch (feign.RetryableException failure) {
            return Results.success(pending(commandId, null));
        }
    }

    @Override
    public Result<Map<String, Object>> currentPolicies(long linkId, String cursor) {
        trustedUser();
        if (linkId < 1 || (cursor != null && cursor.length() > 128))
            throw new ClientException("Invalid policy cursor or resource");
        try {
            return Results.success(commands.policies(linkId, cursor));
        } catch (feign.RetryableException failure) {
            return Results.success(
                    Map.of(
                            "state",
                            "UNKNOWN",
                            "asOf",
                            System.currentTimeMillis(),
                            "policies",
                            List.of()));
        } catch (feign.FeignException failure) {
            if (failure.status() >= 500)
                return Results.success(
                        Map.of(
                                "state",
                                "UNKNOWN",
                                "asOf",
                                System.currentTimeMillis(),
                                "policies",
                                List.of()));
            throw failure;
        }
    }

    private void enrich(List<RiskShortLinkCardRespDTO> cards, String gid, TrustedUser user) {
        if (cards == null) return;
        if (cards.size() > 500) throw new ClientException("Risk center page exceeds its budget");
        for (var card : cards) {
            if (card == null
                    || !user.userId().equals(card.getTenantId())
                    || card.getLinkId() == null
                    || card.getLinkId() < 1)
                throw new ClientException("Risk center resource is unavailable");
            requireReturnedGid(gid, card.getGid());
            card.setCurrentPolicy(Map.of("state", "UNKNOWN", "propagationState", "UNKNOWN"));
        }
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        for (int start = 0; start < cards.size() && System.nanoTime() < deadline; start += 100) {
            List<RiskShortLinkCardRespDTO> batch =
                    cards.subList(start, Math.min(cards.size(), start + 100));
            try {
                var snapshots =
                        commands.current(
                                new CommandRiskRemoteService.Current(
                                        batch.stream()
                                                .map(RiskShortLinkCardRespDTO::getLinkId)
                                                .distinct()
                                                .toList()));
                if (snapshots == null || snapshots.size() > 100) break;
                Map<String, CommandRiskRemoteService.Snapshot> indexed = new HashMap<>();
                for (var snapshot : snapshots)
                    if (snapshot != null) indexed.put(snapshot.resourceKey(), snapshot);
                long now = System.currentTimeMillis();
                for (var card : batch) {
                    var snapshot = indexed.get(user.userId() + ":" + card.getLinkId());
                    if (snapshot == null
                            || now < snapshot.evaluatedAt()
                            || now >= snapshot.validUntil()
                            || snapshot.validUntil() - snapshot.evaluatedAt() > 1000
                            || (snapshot.nextTransitionAt() != null
                                    && now >= snapshot.nextTransitionAt())
                            || !Set.of("KNOWN_ALLOWED", "KNOWN_RESTRICTED")
                                    .contains(snapshot.state())) continue;
                    Map<String, Object> state = new LinkedHashMap<>();
                    state.put("state", snapshot.state());
                    state.put("policyRevision", snapshot.policyRevision());
                    state.put("asOf", snapshot.evaluatedAt());
                    state.put("nextTransitionAt", snapshot.nextTransitionAt());
                    state.put("disabled", snapshot.disabled());
                    state.put("propagationState", "NOT_OBSERVED");
                    card.setCurrentPolicy(state);
                }
            } catch (feign.RetryableException failure) {
                break;
            } catch (feign.FeignException failure) {
                if (failure.status() >= 500) break;
                throw failure;
            }
        }
    }

    private Map<String, Object> receipt(CommandRiskRemoteService.Receipt receipt) {
        if (receipt == null) throw new ClientException("Command receipt is unavailable");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("commandId", receipt.commandId());
        value.put("policyId", receipt.policyId());
        value.put("commandState", receipt.status());
        value.put("policyRevision", receipt.policyRevision());
        value.put("committedAt", receipt.committedAt());
        value.put(
                "propagationState",
                "COMMITTED".equals(receipt.status()) ? "PENDING" : "NOT_APPLICABLE");
        return value;
    }

    private Map<String, Object> pending(String commandId, String policyId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("commandId", commandId);
        value.put("policyId", policyId);
        value.put("commandState", "PENDING_CONFIRMATION");
        value.put("propagationState", "UNKNOWN");
        return value;
    }

    private TrustedUser trustedUser() {
        String username = UserContext.getUsername(), tenant = UserContext.getUserId();
        Long version = UserContext.getAuthVersion();
        if (username == null
                || username.isBlank()
                || tenant == null
                || !tenant.matches("[1-9][0-9]{0,18}")
                || version == null
                || version < 1)
            throw new ClientException("Risk center request requires authenticated user context");
        return new TrustedUser(username, tenant, version);
    }

    private void requireOwnedGid(String gid) {
        if (gid == null || gid.isBlank())
            throw new ClientException("Risk center request gid is not owned by current user");
        Long count =
                groupService.count(
                        Wrappers.lambdaQuery(GroupDO.class)
                                .eq(GroupDO::getUsername, UserContext.getUsername())
                                .eq(GroupDO::getGid, gid)
                                .eq(GroupDO::getDelFlag, 0));
        if (count == null || count < 1)
            throw new ClientException("Risk center request gid is not owned by current user");
    }

    private void requireReturnedGid(String expected, String actual) {
        if (!expected.equals(actual))
            throw new ClientException("Risk center request gid is not owned by current user");
    }

    private String internalToken() {
        String token = agentAdminConfiguration.getInternalToken();
        if (token == null || token.isBlank())
            throw new ClientException("Risk center service credentials are unavailable");
        return token;
    }

    private record TrustedUser(String username, String userId, long authVersion) {}
}
