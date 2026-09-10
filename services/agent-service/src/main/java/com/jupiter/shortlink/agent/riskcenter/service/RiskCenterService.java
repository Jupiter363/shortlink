package com.jupiter.shortlink.agent.riskcenter.service;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient;
import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskEventQueryReqDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskEventRespDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskGroupOverviewRespDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskReviewReqDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskReviewRespDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskShortLinkCardRespDTO;
import com.jupiter.shortlink.agent.riskcenter.api.dto.RiskShortLinkDetailRespDTO;
import com.jupiter.shortlink.agent.riskcenter.model.RiskEvent;
import com.jupiter.shortlink.agent.riskcenter.model.RiskReview;
import com.jupiter.shortlink.agent.riskcenter.model.RiskSnapshot;
import com.jupiter.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.jupiter.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.jupiter.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.jupiter.shortlink.agent.riskcommon.model.RiskEventSource;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.jupiter.shortlink.agent.riskcommon.model.RiskTargetType;
import com.jupiter.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.jupiter.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.jupiter.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RiskCenterService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final JdbcRiskEventRepository eventRepository;
    private final JdbcRiskSnapshotRepository snapshotRepository;
    private final JdbcRiskReviewRepository reviewRepository;
    private final JdbcShortLinkRiskProfileRepository shortLinkProfileRepository;
    private final JdbcGroupRiskProfileRepository groupProfileRepository;
    private final RiskPolicyService riskPolicyService;
    private final Clock clock;
    private final AgentAuthorityClient authority;

    @Autowired
    public RiskCenterService(
            JdbcRiskEventRepository eventRepository,
            JdbcRiskSnapshotRepository snapshotRepository,
            JdbcRiskReviewRepository reviewRepository,
            JdbcShortLinkRiskProfileRepository shortLinkProfileRepository,
            JdbcGroupRiskProfileRepository groupProfileRepository,
            RiskPolicyService riskPolicyService,
            AgentAuthorityClient authority) {
        this(
                eventRepository,
                snapshotRepository,
                reviewRepository,
                shortLinkProfileRepository,
                groupProfileRepository,
                riskPolicyService,
                Clock.system(SHANGHAI),
                authority);
    }

    public RiskCenterService(
            JdbcRiskEventRepository events,
            JdbcRiskSnapshotRepository snapshots,
            JdbcRiskReviewRepository reviews,
            JdbcShortLinkRiskProfileRepository profiles,
            JdbcGroupRiskProfileRepository groups,
            RiskPolicyService policies) {
        this(events, snapshots, reviews, profiles, groups, policies, Clock.system(SHANGHAI), null);
    }

    RiskCenterService(
            JdbcRiskEventRepository eventRepository,
            JdbcRiskSnapshotRepository snapshotRepository,
            JdbcRiskReviewRepository reviewRepository,
            JdbcShortLinkRiskProfileRepository shortLinkProfileRepository,
            JdbcGroupRiskProfileRepository groupProfileRepository,
            RiskPolicyService riskPolicyService,
            Clock clock,
            AgentAuthorityClient authority) {
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.reviewRepository = reviewRepository;
        this.shortLinkProfileRepository = shortLinkProfileRepository;
        this.groupProfileRepository = groupProfileRepository;
        this.riskPolicyService = riskPolicyService;
        this.clock = clock;
        this.authority = authority;
    }

    public RiskGroupOverviewRespDTO getGroupOverview(String gid) {
        throw new SecurityException("Trusted principal is required");
    }

    public RiskGroupOverviewRespDTO getGroupOverview(AgentPrincipal principal, String gid) {
        AuthorizedScope scope = authorize(principal, gid, null, null);
        GroupRiskProfile profile =
                groupProfileRepository
                        .findAuthorized(scope, gid, null)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Group risk profile is not available"));
        Map<Long, Map<String, Object>> reviews = reviewRepository.latestStates(scope);
        List<RiskShortLinkCardRespDTO> topRiskShortLinks =
                shortLinkProfileRepository.findAuthorized(scope, null, 10).stream()
                        .map(value -> toCard(value, scope, reviews))
                        .toList();
        return new RiskGroupOverviewRespDTO(
                profile.gid(),
                profile.totalShortLinksScanned(),
                profile.lowRiskCount(),
                profile.mediumRiskCount(),
                profile.highRiskCount(),
                reviews.values().stream()
                        .filter(value -> "WATCHING".equals(value.get("watchStatus")))
                        .count(),
                null,
                profile.avgRiskScore(),
                profile.maxRiskScore(),
                profile.groupRiskScore(),
                profile.groupRiskLevel().name(),
                profile.groupReasonCodes().stream().map(RiskReasonCode::name).toList(),
                topRiskShortLinks,
                profile.riskTrend7d().stream().map(this::toTrendMap).toList(),
                profile.agentSummary(),
                reviewRepository.latestGroupState(scope, gid));
    }

    public List<RiskShortLinkCardRespDTO> listGroupShortLinkCards(
            AgentPrincipal principal, String gid) {
        AuthorizedScope scope = authorize(principal, gid, null, null);
        Map<Long, Map<String, Object>> reviews = reviewRepository.latestStates(scope);
        return shortLinkProfileRepository.findAuthorized(scope, null, 500).stream()
                .sorted(
                        Comparator.comparingInt(ShortLinkRiskProfile::riskScore)
                                .reversed()
                                .thenComparing(ShortLinkRiskProfile::shortUri))
                .map(value -> toCard(value, scope, reviews))
                .toList();
    }

    public RiskShortLinkDetailRespDTO getShortLinkRisk(
            AgentPrincipal principal, String gid, String domain, String shortUri) {
        AuthorizedScope scope = authorize(principal, gid, domain + "/" + shortUri, null);
        ShortLinkRiskProfile profile =
                shortLinkProfileRepository.findAuthorized(scope, null, 1).stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Risk profile not found: "
                                                        + gid
                                                        + "/"
                                                        + domain
                                                        + "/"
                                                        + shortUri));
        List<RiskEventRespDTO> recentEvents =
                eventRepository.listAuthorized(scope, 1, 10).stream()
                        .map(this::toEventResp)
                        .toList();
        Map<String, Object> latestSnapshot =
                snapshotRepository
                        .findAuthorized(scope, profile.evidence().linkId())
                        .map(this::toSnapshotMap)
                        .orElseGet(Map::of);
        return new RiskShortLinkDetailRespDTO(
                toCard(profile, scope, reviewRepository.latestStates(scope)),
                profile.metrics(),
                latestSnapshot,
                recentEvents);
    }

    public PageResult<RiskEventRespDTO> listEvents(
            AgentPrincipal principal, RiskEventQueryReqDTO query) {
        AuthorizedScope scope =
                authorize(
                        principal,
                        query.gid(),
                        StringUtils.hasText(query.domain()) && StringUtils.hasText(query.shortUri())
                                ? query.domain() + "/" + query.shortUri()
                                : null,
                        null);
        RiskTargetType targetType = targetType(query.targetType());
        int pageNo = safePageNo(query.pageNo());
        int pageSize = safePageSize(query.pageSize());
        List<RiskEventRespDTO> events =
                (targetType == RiskTargetType.SHORT_LINK
                                ? eventRepository.listAuthorized(scope, pageNo, pageSize)
                                : eventRepository.listAuthorizedGroup(
                                        scope, query.gid(), pageNo, pageSize))
                        .stream().map(this::toEventResp).toList();
        long total =
                targetType == RiskTargetType.SHORT_LINK
                        ? eventRepository.countAuthorized(scope)
                        : eventRepository.countAuthorizedGroup(scope, query.gid());
        return new PageResult<>(events, total, pageNo, pageSize);
    }

    public RiskReviewRespDTO submitReview(AgentPrincipal principal, RiskReviewReqDTO request) {
        RiskTargetType target = targetType(request.targetType());
        AuthorizedScope scope =
                authorize(
                        principal,
                        request.gid(),
                        target == RiskTargetType.SHORT_LINK
                                ? request.domain() + "/" + request.shortUri()
                                : null,
                        null);
        if (target == RiskTargetType.SHORT_LINK && scope.links().size() != 1)
            throw new SecurityException("Review resource is unavailable");
        Long linkId =
                target == RiskTargetType.SHORT_LINK
                        ? StatsEvidence.number(scope.links().get(0).get("linkId"))
                        : null;
        if (StringUtils.hasText(request.eventId())
                && (target == RiskTargetType.SHORT_LINK
                                ? eventRepository.findAuthorizedEvent(scope, request.eventId())
                                : eventRepository.findAuthorizedGroupEvent(
                                        scope, request.gid(), request.eventId()))
                        .isEmpty()) throw new SecurityException("Review event is unavailable");
        RiskReview review =
                new RiskReview(
                        "review-" + UUID.randomUUID(),
                        request.eventId(),
                        targetType(request.targetType()),
                        request.gid(),
                        target == RiskTargetType.SHORT_LINK ? request.domain() : "",
                        target == RiskTargetType.SHORT_LINK ? request.shortUri() : "",
                        target == RiskTargetType.SHORT_LINK ? request.fullShortUrl() : "",
                        reviewAction(request.reviewAction()),
                        principal.username(),
                        request.reviewNote(),
                        LocalDateTime.now(clock),
                        scope.tenantId(),
                        linkId);
        reviewRepository.saveReview(review);
        return toReviewResp(review);
    }

    public void disablePolicy(
            String policyId, String gid, String reviewer, String reason, String traceId) {
        throw new SecurityException(
                "Explicit policy revocation must use the authorized Admin Command endpoint");
    }

    public RiskEvent recordProfileBatchEvent(ShortLinkRiskProfile profile, String traceId) {
        return recordRiskEventFromProfile(
                profile,
                traceId,
                "",
                profile.latestAgentSummary(),
                RiskEventSource.PROFILE_BATCH,
                "");
    }

    public RiskEvent recordProfileBatchEvent(
            ShortLinkRiskProfile profile, String traceId, String sessionId, String agentSummary) {
        return recordRiskEventFromProfile(
                profile, traceId, sessionId, agentSummary, RiskEventSource.PROFILE_BATCH, "");
    }

    public RiskEvent recordSecurityRiskAgentEvent(
            ShortLinkRiskProfile profile, String traceId, String sessionId, String agentSummary) {
        return recordRiskEventFromProfile(
                profile,
                traceId,
                sessionId,
                agentSummary,
                RiskEventSource.SECURITY_RISK_AGENT,
                securityRiskEventId(profile, traceId));
    }

    private RiskEvent recordRiskEventFromProfile(
            ShortLinkRiskProfile profile,
            String traceId,
            String sessionId,
            String agentSummary,
            RiskEventSource source,
            String eventId) {
        RiskEvent event =
                new RiskEvent(
                        StringUtils.hasText(eventId) ? eventId : "risk-event-" + UUID.randomUUID(),
                        RiskTargetType.SHORT_LINK,
                        profile.gid(),
                        profile.domain(),
                        profile.shortUri(),
                        profile.fullShortUrl(),
                        profile.riskScore(),
                        profile.riskLevel(),
                        List.copyOf(profile.reasonCodes()),
                        evidenceFromProfile(profile),
                        profile.latestPolicyActions(),
                        valueOrDefault(agentSummary, profile.latestAgentSummary()),
                        traceId,
                        sessionId,
                        source,
                        profile.profileWindowEnd());
        eventRepository.saveEvent(event);
        return event;
    }

    private String securityRiskEventId(ShortLinkRiskProfile profile, String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return "";
        }
        String idempotencyKey =
                String.join(
                        "|",
                        traceId,
                        profile.gid(),
                        profile.domain(),
                        profile.shortUri(),
                        profile.profileWindowEnd() == null
                                ? ""
                                : profile.profileWindowEnd().toString());
        return "risk-event-"
                + UUID.nameUUIDFromBytes(idempotencyKey.getBytes(StandardCharsets.UTF_8));
    }

    public void upsertSnapshotFromProfile(
            ShortLinkRiskProfile profile, String eventId, String traceId) {
        snapshotRepository.upsertSnapshot(
                new RiskSnapshot(
                        RiskTargetType.SHORT_LINK,
                        profile.gid(),
                        profile.domain(),
                        profile.shortUri(),
                        profile.fullShortUrl(),
                        profile.riskScore(),
                        profile.riskLevel(),
                        List.copyOf(profile.reasonCodes()),
                        riskCardsFromProfile(profile),
                        profile.watchStatus(),
                        "UNKNOWN",
                        eventId,
                        traceId,
                        profile.profileWindowEnd()));
    }

    private RiskShortLinkCardRespDTO toCard(
            ShortLinkRiskProfile profile,
            AuthorizedScope scope,
            Map<Long, Map<String, Object>> reviews) {
        if (profile.evidence() == null || !scope.tenantId().equals(profile.evidence().tenantId()))
            throw new SecurityException("Stored profile identity is unavailable");
        Map<String, Object> link =
                scope.links().stream()
                        .filter(
                                value ->
                                        StatsEvidence.number(value.get("linkId"))
                                                == profile.evidence().linkId())
                        .findFirst()
                        .orElseThrow(
                                () -> new SecurityException("Resource is no longer authorized"));
        Map<String, Object> manual = reviews.getOrDefault(profile.evidence().linkId(), Map.of());
        ShortLinkRiskMetrics metrics = profile.metrics();
        return new RiskShortLinkCardRespDTO(
                String.valueOf(link.get("gid")),
                profile.domain(),
                profile.shortUri(),
                profile.fullShortUrl(),
                profile.profileWindowEnd() == null ? "" : profile.profileWindowEnd().toString(),
                profile.riskScore(),
                profile.riskLevel().name(),
                profile.reasonCodes().stream().map(RiskReasonCode::name).sorted().toList(),
                metrics.pv2h(),
                metrics.uv2h(),
                metrics.pv24h(),
                metrics.uv24h(),
                metrics.pv7d(),
                metrics.uv7d(),
                String.valueOf(manual.getOrDefault("watchStatus", "NONE")),
                profile.latestPolicyActions(),
                profile.latestAgentSummary(),
                scope.tenantId(),
                profile.evidence().linkId(),
                profile.evidence().meta(),
                Map.of("state", "UNKNOWN"),
                manual);
    }

    private RiskEventRespDTO toEventResp(RiskEvent event) {
        return new RiskEventRespDTO(
                event.eventId(),
                event.targetType().name(),
                event.gid(),
                event.domain(),
                event.shortUri(),
                event.fullShortUrl(),
                event.riskScore(),
                event.riskLevel().name(),
                event.reasonCodes().stream().map(RiskReasonCode::name).toList(),
                event.evidence(),
                event.recommendedActions(),
                event.agentSummary(),
                event.traceId(),
                event.sessionId(),
                event.source().name(),
                event.eventTime() == null ? "" : event.eventTime().toString());
    }

    private RiskReviewRespDTO toReviewResp(RiskReview review) {
        return new RiskReviewRespDTO(
                review.reviewId(),
                review.eventId(),
                review.targetType().name(),
                review.gid(),
                review.domain(),
                review.shortUri(),
                review.fullShortUrl(),
                review.reviewAction().name(),
                review.reviewer(),
                review.reviewNote(),
                review.reviewTime() == null ? "" : review.reviewTime().toString());
    }

    private Map<String, Object> toSnapshotMap(RiskSnapshot snapshot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("targetType", snapshot.targetType().name());
        value.put("gid", snapshot.gid());
        value.put("domain", snapshot.domain());
        value.put("shortUri", snapshot.shortUri());
        value.put("fullShortUrl", snapshot.fullShortUrl());
        value.put("riskScore", snapshot.riskScore());
        value.put("riskLevel", snapshot.riskLevel().name());
        value.put(
                "reasonCodes", snapshot.reasonCodes().stream().map(RiskReasonCode::name).toList());
        value.put("riskCards", snapshot.riskCards());
        value.put("watchStatus", snapshot.watchStatus().name());
        value.put("policyStatusSource", "HISTORICAL_ONLY");
        value.put("lastEventId", snapshot.lastEventId());
        value.put("lastTraceId", snapshot.lastTraceId());
        value.put(
                "lastScanTime",
                snapshot.lastScanTime() == null ? "" : snapshot.lastScanTime().toString());
        return value;
    }

    private Map<String, Object> toTrendMap(RiskTrendPoint point) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("date", point.date().toString());
        value.put("riskScore", point.riskScore());
        value.put("riskLevel", point.riskLevel().name());
        return value;
    }

    private Map<String, Object> evidenceFromProfile(ShortLinkRiskProfile profile) {
        ShortLinkRiskMetrics metrics = profile.metrics();
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (profile.evidence() != null) {
            evidence.put("tenantId", profile.evidence().tenantId());
            evidence.put("linkId", profile.evidence().linkId());
            evidence.put("statsMeta", profile.evidence().meta());
        } else evidence.put("statsMeta", Map.of("availability", "UNKNOWN"));
        evidence.put("pv2h", metrics.pv2h());
        evidence.put("uv2h", metrics.uv2h());
        evidence.put("pv24h", metrics.pv24h());
        evidence.put("uv24h", metrics.uv24h());
        evidence.put("pv7d", metrics.pv7d());
        evidence.put("uv7d", metrics.uv7d());
        putIfNotNull(evidence, "pvGrowth2hVs24hAvg", metrics.pvGrowth2hVs24hAvg());
        putIfNotNull(evidence, "topShare", metrics.topIpShare());
        putIfNotNull(evidence, "topRegionShare", metrics.topRegionShare());
        putIfNotNull(evidence, "topDeviceShare", metrics.topDeviceShare());
        putIfNotNull(evidence, "topBrowserShare", metrics.topBrowserShare());
        putIfNotNull(evidence, "pvPerUv", metrics.pvPerUv());
        putIfNotNull(evidence, "peakHourShare", metrics.peakHourShare());
        putIfNotNull(evidence, "repeatVisitRatio", metrics.repeatVisitRatio());
        return evidence;
    }

    private List<Map<String, Object>> riskCardsFromProfile(ShortLinkRiskProfile profile) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "risk-profile");
        card.put("riskScore", profile.riskScore());
        card.put("riskLevel", profile.riskLevel().name());
        card.put(
                "reasonCodes",
                profile.reasonCodes().stream().map(RiskReasonCode::name).sorted().toList());
        card.put("metrics", evidenceFromProfile(profile));
        return List.of(card);
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private RiskTargetType targetType(String value) {
        if (!StringUtils.hasText(value)) {
            return RiskTargetType.SHORT_LINK;
        }
        return RiskTargetType.valueOf(value);
    }

    private RiskReviewAction reviewAction(String value) {
        if (!StringUtils.hasText(value)) {
            return RiskReviewAction.IGNORE;
        }
        return RiskReviewAction.valueOf(value);
    }

    private int safePageNo(int pageNo) {
        if (pageNo > 10000) throw new IllegalArgumentException("Risk history page exceeds budget");
        return Math.max(1, pageNo);
    }

    private int safePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private AuthorizedScope authorize(
            AgentPrincipal principal, String gid, String fullUrl, List<Long> links) {
        if (principal == null
                || principal.system()
                || authority == null
                || !StringUtils.hasText(gid))
            throw new SecurityException("Trusted interactive principal and gid are required");
        return authority.resolve(principal, gid, fullUrl, links);
    }

    private String valueOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    public record PageResult<T>(List<T> records, long total, int pageNo, int pageSize) {}
}
