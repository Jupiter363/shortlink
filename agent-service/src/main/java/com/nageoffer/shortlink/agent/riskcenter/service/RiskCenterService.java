package com.nageoffer.shortlink.agent.riskcenter.service;

import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskEventQueryReqDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskEventRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskGroupOverviewRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskReviewReqDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskReviewRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskShortLinkCardRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.api.dto.RiskShortLinkDetailRespDTO;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskEvent;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskReview;
import com.nageoffer.shortlink.agent.riskcenter.model.RiskSnapshot;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskEventRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskReviewRepository;
import com.nageoffer.shortlink.agent.riskcenter.repository.JdbcRiskSnapshotRepository;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskEventSource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicyDisableCommand;
import com.nageoffer.shortlink.agent.riskpolicy.service.RiskPolicyService;
import com.nageoffer.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.model.RiskTrendPoint;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.nageoffer.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcGroupRiskProfileRepository;
import com.nageoffer.shortlink.agent.riskprofile.repository.JdbcShortLinkRiskProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RiskCenterService {

    private final JdbcRiskEventRepository eventRepository;
    private final JdbcRiskSnapshotRepository snapshotRepository;
    private final JdbcRiskReviewRepository reviewRepository;
    private final JdbcShortLinkRiskProfileRepository shortLinkProfileRepository;
    private final JdbcGroupRiskProfileRepository groupProfileRepository;
    private final RiskPolicyService riskPolicyService;
    private final Clock clock;

    public RiskCenterService(
            JdbcRiskEventRepository eventRepository,
            JdbcRiskSnapshotRepository snapshotRepository,
            JdbcRiskReviewRepository reviewRepository,
            JdbcShortLinkRiskProfileRepository shortLinkProfileRepository,
            JdbcGroupRiskProfileRepository groupProfileRepository,
            RiskPolicyService riskPolicyService
    ) {
        this(
                eventRepository,
                snapshotRepository,
                reviewRepository,
                shortLinkProfileRepository,
                groupProfileRepository,
                riskPolicyService,
                Clock.systemDefaultZone()
        );
    }

    RiskCenterService(
            JdbcRiskEventRepository eventRepository,
            JdbcRiskSnapshotRepository snapshotRepository,
            JdbcRiskReviewRepository reviewRepository,
            JdbcShortLinkRiskProfileRepository shortLinkProfileRepository,
            JdbcGroupRiskProfileRepository groupProfileRepository,
            RiskPolicyService riskPolicyService,
            Clock clock
    ) {
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.reviewRepository = reviewRepository;
        this.shortLinkProfileRepository = shortLinkProfileRepository;
        this.groupProfileRepository = groupProfileRepository;
        this.riskPolicyService = riskPolicyService;
        this.clock = clock;
    }

    public RiskGroupOverviewRespDTO getGroupOverview(String gid) {
        GroupRiskProfile profile = groupProfileRepository.findLatestByGid(gid)
                .orElse(null);
        List<RiskShortLinkCardRespDTO> topRiskShortLinks = shortLinkProfileRepository.findTopRiskByGid(gid, 10).stream()
                .map(this::toCard)
                .toList();
        if (profile == null) {
            return new RiskGroupOverviewRespDTO(
                    valueOrEmpty(gid),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0D,
                    0,
                    0,
                    RiskLevel.LOW.name(),
                    List.of(),
                    topRiskShortLinks,
                    List.of(),
                    ""
            );
        }
        return new RiskGroupOverviewRespDTO(
                profile.gid(),
                profile.totalShortLinksScanned(),
                profile.lowRiskCount(),
                profile.mediumRiskCount(),
                profile.highRiskCount(),
                profile.watchingCount(),
                profile.disabledCount(),
                profile.avgRiskScore(),
                profile.maxRiskScore(),
                profile.groupRiskScore(),
                profile.groupRiskLevel().name(),
                profile.groupReasonCodes().stream().map(RiskReasonCode::name).toList(),
                topRiskShortLinks,
                profile.riskTrend7d().stream().map(this::toTrendMap).toList(),
                profile.agentSummary()
        );
    }

    public List<RiskShortLinkCardRespDTO> listGroupShortLinkCards(String gid) {
        return shortLinkProfileRepository.findLatestByGid(gid).stream()
                .sorted(Comparator.comparingInt(ShortLinkRiskProfile::riskScore).reversed()
                        .thenComparing(ShortLinkRiskProfile::shortUri))
                .map(this::toCard)
                .toList();
    }

    public RiskShortLinkDetailRespDTO getShortLinkRisk(String domain, String shortUri) {
        ShortLinkRiskProfile profile = shortLinkProfileRepository.findLatest(domain, shortUri)
                .orElseThrow(() -> new IllegalArgumentException("Risk profile not found: " + domain + "/" + shortUri));
        List<RiskEventRespDTO> recentEvents = eventRepository.listEvents(
                        profile.gid(),
                        RiskTargetType.SHORT_LINK,
                        domain,
                        shortUri,
                        1,
                        10
                ).stream()
                .map(this::toEventResp)
                .toList();
        Map<String, Object> latestSnapshot = snapshotRepository
                .findByTarget(RiskTargetType.SHORT_LINK, profile.gid(), domain, shortUri)
                .map(this::toSnapshotMap)
                .orElseGet(Map::of);
        return new RiskShortLinkDetailRespDTO(
                toCard(profile),
                profile.metrics(),
                latestSnapshot,
                recentEvents
        );
    }

    public PageResult<RiskEventRespDTO> listEvents(RiskEventQueryReqDTO query) {
        RiskTargetType targetType = targetType(query.targetType());
        int pageNo = safePageNo(query.pageNo());
        int pageSize = safePageSize(query.pageSize());
        List<RiskEventRespDTO> events = eventRepository.listEvents(
                        query.gid(),
                        targetType,
                        query.domain(),
                        query.shortUri(),
                        pageNo,
                        pageSize
                ).stream()
                .map(this::toEventResp)
                .toList();
        long total = eventRepository.countEvents(query.gid(), targetType, query.domain(), query.shortUri());
        return new PageResult<>(events, total, pageNo, pageSize);
    }

    public RiskReviewRespDTO submitReview(RiskReviewReqDTO request) {
        RiskReview review = new RiskReview(
                "review-" + UUID.randomUUID(),
                request.eventId(),
                targetType(request.targetType()),
                request.gid(),
                request.domain(),
                request.shortUri(),
                request.fullShortUrl(),
                reviewAction(request.reviewAction()),
                request.reviewer(),
                request.reviewNote(),
                LocalDateTime.now(clock)
        );
        reviewRepository.saveReview(review);
        applyReviewToSnapshot(review);
        return toReviewResp(review);
    }

    public void disablePolicy(String policyId, String reviewer, String reason, String traceId) {
        riskPolicyService.disablePolicy(new RiskPolicyDisableCommand(
                policyId,
                valueOrDefault(reviewer, "unknown"),
                valueOrDefault(reason, "manual risk policy disable"),
                valueOrDefault(traceId, "risk-policy-disable-" + UUID.randomUUID())
        ));
    }

    public RiskEvent recordProfileBatchEvent(ShortLinkRiskProfile profile, String traceId) {
        return recordRiskEventFromProfile(profile, traceId, "", profile.latestAgentSummary(), RiskEventSource.PROFILE_BATCH);
    }

    public RiskEvent recordProfileBatchEvent(ShortLinkRiskProfile profile, String traceId, String sessionId, String agentSummary) {
        return recordRiskEventFromProfile(profile, traceId, sessionId, agentSummary, RiskEventSource.PROFILE_BATCH);
    }

    public RiskEvent recordSecurityRiskAgentEvent(
            ShortLinkRiskProfile profile,
            String traceId,
            String sessionId,
            String agentSummary
    ) {
        return recordRiskEventFromProfile(profile, traceId, sessionId, agentSummary, RiskEventSource.SECURITY_RISK_AGENT);
    }

    private RiskEvent recordRiskEventFromProfile(
            ShortLinkRiskProfile profile,
            String traceId,
            String sessionId,
            String agentSummary,
            RiskEventSource source
    ) {
        RiskEvent event = new RiskEvent(
                "risk-event-" + UUID.randomUUID(),
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
                profile.profileWindowEnd()
        );
        eventRepository.saveEvent(event);
        return event;
    }

    public void upsertSnapshotFromProfile(ShortLinkRiskProfile profile, String eventId, String traceId) {
        snapshotRepository.upsertSnapshot(new RiskSnapshot(
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
                profile.latestPolicyActions().isEmpty() ? "NONE" : "ACTIVE",
                eventId,
                traceId,
                profile.profileWindowEnd()
        ));
    }

    private void applyReviewToSnapshot(RiskReview review) {
        if (review.reviewAction() == RiskReviewAction.WATCH) {
            snapshotRepository.updateWatchStatus(
                    review.targetType(),
                    review.gid(),
                    review.domain(),
                    review.shortUri(),
                    RiskWatchStatus.WATCHING
            );
            return;
        }
        if (review.reviewAction() == RiskReviewAction.UNWATCH) {
            snapshotRepository.updateWatchStatus(
                    review.targetType(),
                    review.gid(),
                    review.domain(),
                    review.shortUri(),
                    RiskWatchStatus.NONE
            );
            return;
        }
        if (review.reviewAction() == RiskReviewAction.FALSE_POSITIVE) {
            snapshotRepository.markFalsePositive(
                    review.targetType(),
                    review.gid(),
                    review.domain(),
                    review.shortUri()
            );
        }
    }

    private RiskShortLinkCardRespDTO toCard(ShortLinkRiskProfile profile) {
        ShortLinkRiskMetrics metrics = profile.metrics();
        return new RiskShortLinkCardRespDTO(
                profile.gid(),
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
                profile.watchStatus().name(),
                profile.latestPolicyActions(),
                profile.latestAgentSummary()
        );
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
                event.eventTime() == null ? "" : event.eventTime().toString()
        );
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
                review.reviewTime() == null ? "" : review.reviewTime().toString()
        );
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
        value.put("reasonCodes", snapshot.reasonCodes().stream().map(RiskReasonCode::name).toList());
        value.put("riskCards", snapshot.riskCards());
        value.put("watchStatus", snapshot.watchStatus().name());
        value.put("policyStatus", snapshot.policyStatus());
        value.put("lastEventId", snapshot.lastEventId());
        value.put("lastTraceId", snapshot.lastTraceId());
        value.put("lastScanTime", snapshot.lastScanTime() == null ? "" : snapshot.lastScanTime().toString());
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
        card.put("reasonCodes", profile.reasonCodes().stream().map(RiskReasonCode::name).sorted().toList());
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

    private String valueOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    public record PageResult<T>(
            List<T> records,
            long total,
            int pageNo,
            int pageSize
    ) {
    }
}
