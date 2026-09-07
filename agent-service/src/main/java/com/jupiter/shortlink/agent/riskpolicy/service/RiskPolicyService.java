package com.jupiter.shortlink.agent.riskpolicy.service;

import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.riskcommon.model.*;
import com.jupiter.shortlink.agent.riskpolicy.model.*;
import com.jupiter.shortlink.agent.riskpolicy.repository.JdbcRiskActionAuditRepository;
import com.jupiter.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;

/** Agent records evidence; Command alone owns policy state and durable mutation receipts. */
@Service
public class RiskPolicyService {
    private final CommandPolicyClient commands;
    private final AgentProperties properties;
    private final Clock clock;

    @Autowired
    public RiskPolicyService(CommandPolicyClient commands, AgentProperties properties) {
        this(commands, properties, Clock.systemUTC());
    }

    public RiskPolicyService(
            CommandPolicyClient commands, AgentProperties properties, Clock clock) {
        this.commands = commands;
        this.properties = properties;
        this.clock = clock;
    }

    @Deprecated(forRemoval = true)
    public RiskPolicyService(
            JdbcRiskPolicyRepository ignored,
            JdbcRiskActionAuditRepository audit,
            RiskPolicyRedisPublisher publisher,
            AgentProperties properties) {
        this(null, properties, Clock.systemUTC());
    }

    public Map<String, Object> autoLimitRate(
            AgentPrincipal principal,
            ShortLinkRiskProfile profile,
            String commandId,
            String policyId) {
        if (principal == null || commands == null)
            throw new SecurityException("Trusted policy principal is required");
        // Read committed commands under current permissions before inspecting old evidence or
        // attempting a write.
        Map<String, Object> existing = commands.result(principal, commandId);
        if (existing != null) return existing;
        StatsEvidence evidence = profile.evidence();
        if (evidence == null
                || (!principal.system() && !principal.tenantId().equals(evidence.tenantId()))
                || !evidence.permitsAutomaticAction(clock.millis())
                || profile.metrics() == null
                || profile.metrics().pv2h() < 100
                || profile.reasonCodes().stream()
                                .filter(evidence::supportsAutomaticReason)
                                .filter(
                                        reason ->
                                                reason != RiskReasonCode.HIGH_REPEAT_VISIT
                                                        || profile.metrics().uv2h() >= 20)
                                .count()
                        < 2) {
            return Map.of(
                    "commandId", commandId, "status", "EVIDENCE_UNAVAILABLE", "policyId", policyId);
        }
        List<Map<String, Object>> current = commands.current(principal, List.of(evidence.linkId()));
        if (current.size() != 1)
            throw new IllegalStateException("Current policy snapshot is unavailable");
        Map<String, Object> snapshot = current.get(0);
        if (!Objects.equals(
                snapshot.get("resourceKey"), evidence.tenantId() + ":" + evidence.linkId())) {
            throw new SecurityException("Policy snapshot scope mismatch");
        }
        if (!Set.of("KNOWN_ALLOWED", "KNOWN_RESTRICTED").contains(snapshot.get("state")))
            throw new IllegalStateException("Policy resource is not ready");
        long now = clock.millis();
        if (StatsEvidence.number(snapshot.get("evaluatedAt")) > now
                || StatsEvidence.number(snapshot.get("validUntil")) <= now
                || snapshot.get("nextTransitionAt") != null
                        && StatsEvidence.number(snapshot.get("nextTransitionAt")) <= now)
            throw new IllegalStateException("Policy authority snapshot is no longer current");
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("commandId", commandId);
        command.put("linkId", evidence.linkId());
        command.put("policyId", policyId);
        command.put("action", "LIMIT_RATE");
        command.put(
                "payload",
                Map.of(
                        "rateLimit",
                        Map.of(
                                "limit",
                                properties.getRisk().getAutoAction().getLimitRateLimit(),
                                "windowSeconds",
                                properties.getRisk().getAutoAction().getLimitRateWindowSeconds())));
        command.put("effectiveFrom", now);
        command.put("effectiveUntil", Math.addExact(now, 900_000L));
        command.put("expectedPolicyRevision", StatsEvidence.number(snapshot.get("policyRevision")));
        command.put("evidence", evidence.commandEvidence());
        command.put("automatic", true);
        return commands.activate(principal, Collections.unmodifiableMap(command));
    }

    @Deprecated(forRemoval = true)
    public RiskPolicy activatePolicy(RiskPolicyActivationCommand ignored) {
        throw new SecurityException("Use authorized Command activation with immutable evidence");
    }

    @Deprecated(forRemoval = true)
    public void disablePolicy(RiskPolicyDisableCommand ignored) {
        throw new SecurityException("Use authorized explicit Command revocation");
    }

    public boolean canAutoLimitRate(RiskLevel level, int score, Set<RiskReasonCode> reasons) {
        if (!properties.getRisk().getAutoAction().isLimitRateEnabled()
                || level != RiskLevel.HIGH
                || score < properties.getRisk().getAutoAction().getLimitRateMinScore()
                || reasons == null) return false;
        Set<RiskReasonCode> strong =
                EnumSet.of(
                        RiskReasonCode.TRAFFIC_SPIKE,
                        RiskReasonCode.IP_CONCENTRATION,
                        RiskReasonCode.HIGH_REPEAT_VISIT,
                        RiskReasonCode.PEAK_HOUR_BURST);
        return reasons.stream().filter(strong::contains).count() >= 2;
    }
}
