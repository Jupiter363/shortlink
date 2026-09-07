package com.jupiter.shortlink.agent.riskprofile.repository;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope;
import com.jupiter.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.jupiter.shortlink.agent.riskcommon.model.RiskLevel;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.jupiter.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskMetrics;
import com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class JdbcShortLinkRiskProfileRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskJsonCodec jsonCodec;

    @Autowired
    public JdbcShortLinkRiskProfileRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskJsonCodec());
    }

    public JdbcShortLinkRiskProfileRepository(JdbcTemplate jdbcTemplate, RiskJsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public boolean saveIfLeaseOwned(
            ShortLinkRiskProfile profile, String ownerToken, LocalDateTime leaseCheckTime) {
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must not be blank");
        }
        if (leaseCheckTime == null) {
            throw new IllegalArgumentException("leaseCheckTime must not be null");
        }
        return saveInternal(profile, ownerToken, leaseCheckTime);
    }

    private boolean saveInternal(
            ShortLinkRiskProfile profile, String ownerToken, LocalDateTime leaseCheckTime) {
        ShortLinkRiskMetrics metrics = profile.metrics();
        String reasonCodesJson = jsonCodec.toJson(reasonCodeNames(profile.reasonCodes()));
        String profileJson = jsonCodec.toJson(profileSnapshot(profile));
        if (updateExisting(
                        profile, metrics, reasonCodesJson, profileJson, ownerToken, leaseCheckTime)
                > 0) {
            return true;
        }
        try {
            int insertedRows =
                    jdbcTemplate.update(
                            """
insert into t_agent_short_link_risk_profile (
    batch_id,
    tenant_id,
    link_id,
    evidence_created_at,
    gid,
    domain,
    short_uri,
    full_short_url,
    profile_window_start,
    profile_window_end,
    pv_2h,
    uv_2h,
    pv_24h,
    uv_24h,
    pv_7d,
    uv_7d,
    pv_growth_2h_vs_24h_avg,
    top_ip_share,
    top_visitor_share,
    top_region_share,
    top_device_share,
    top_browser_share,
    pv_per_uv,
    peak_hour_share,
    repeat_visit_ratio,
    anomaly_score,
    risk_score,
    risk_level,
    reason_codes_json,
    profile_json
)
select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
where exists (
    select 1
    from t_agent_risk_profile_batch
    where batch_id = ?
      and owner_token = ?
      and status = ?
      and lease_until is not null
      and lease_until > ?
)
""",
                            profile.batchId(),
                            profile.evidence() == null ? null : profile.evidence().tenantId(),
                            profile.evidence() == null ? null : profile.evidence().linkId(),
                            evidenceCreatedAt(profile),
                            profile.gid(),
                            profile.domain(),
                            profile.shortUri(),
                            profile.fullShortUrl(),
                            Timestamp.valueOf(profile.profileWindowStart()),
                            Timestamp.valueOf(profile.profileWindowEnd()),
                            metrics.pv2h(),
                            metrics.uv2h(),
                            metrics.pv24h(),
                            metrics.uv24h(),
                            metrics.pv7d(),
                            metrics.uv7d(),
                            metrics.pvGrowth2hVs24hAvg(),
                            metrics.topIpShare(),
                            metrics.topVisitorShare(),
                            metrics.topRegionShare(),
                            metrics.topDeviceShare(),
                            metrics.topBrowserShare(),
                            metrics.pvPerUv(),
                            metrics.peakHourShare(),
                            metrics.repeatVisitRatio(),
                            profile.anomalyScore(),
                            profile.riskScore(),
                            profile.riskLevel().name(),
                            reasonCodesJson,
                            profileJson,
                            profile.batchId(),
                            ownerToken,
                            com.jupiter.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus
                                    .RUNNING
                                    .name(),
                            Timestamp.valueOf(leaseCheckTime));
            return insertedRows > 0;
        } catch (DuplicateKeyException ex) {
            return updateExisting(
                            profile,
                            metrics,
                            reasonCodesJson,
                            profileJson,
                            ownerToken,
                            leaseCheckTime)
                    > 0;
        }
    }

    private int updateExisting(
            ShortLinkRiskProfile profile,
            ShortLinkRiskMetrics metrics,
            String reasonCodesJson,
            String profileJson,
            String ownerToken,
            LocalDateTime leaseCheckTime) {
        return jdbcTemplate.update(
                """
                update t_agent_short_link_risk_profile
                set tenant_id = ?, link_id = ?, evidence_created_at = ?, full_short_url = ?,
                    profile_window_start = ?,
                    profile_window_end = ?,
                    pv_2h = ?,
                    uv_2h = ?,
                    pv_24h = ?,
                    uv_24h = ?,
                    pv_7d = ?,
                    uv_7d = ?,
                    pv_growth_2h_vs_24h_avg = ?,
                    top_ip_share = ?,
                    top_visitor_share = ?,
                    top_region_share = ?,
                    top_device_share = ?,
                    top_browser_share = ?,
                    pv_per_uv = ?,
                    peak_hour_share = ?,
                    repeat_visit_ratio = ?,
                    anomaly_score = ?,
                    risk_score = ?,
                    risk_level = ?,
                    reason_codes_json = ?,
                    profile_json = ?,
                    update_time = CURRENT_TIMESTAMP
                where batch_id = ?
                  and gid = ?
                  and domain = ?
                  and short_uri = ?
                  and profile_window_end <= ?
                  and evidence_created_at <= ?
                  and exists (
                      select 1
                      from t_agent_risk_profile_batch
                      where batch_id = ?
                        and owner_token = ?
                        and status = ?
                        and lease_until is not null
                        and lease_until > ?
                  )
                """,
                profile.evidence() == null ? null : profile.evidence().tenantId(),
                profile.evidence() == null ? null : profile.evidence().linkId(),
                evidenceCreatedAt(profile),
                profile.fullShortUrl(),
                Timestamp.valueOf(profile.profileWindowStart()),
                Timestamp.valueOf(profile.profileWindowEnd()),
                metrics.pv2h(),
                metrics.uv2h(),
                metrics.pv24h(),
                metrics.uv24h(),
                metrics.pv7d(),
                metrics.uv7d(),
                metrics.pvGrowth2hVs24hAvg(),
                metrics.topIpShare(),
                metrics.topVisitorShare(),
                metrics.topRegionShare(),
                metrics.topDeviceShare(),
                metrics.topBrowserShare(),
                metrics.pvPerUv(),
                metrics.peakHourShare(),
                metrics.repeatVisitRatio(),
                profile.anomalyScore(),
                profile.riskScore(),
                profile.riskLevel().name(),
                reasonCodesJson,
                profileJson,
                profile.batchId(),
                profile.gid(),
                profile.domain(),
                profile.shortUri(),
                Timestamp.valueOf(profile.profileWindowEnd()),
                evidenceCreatedAt(profile),
                profile.batchId(),
                ownerToken,
                com.jupiter.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus.RUNNING.name(),
                Timestamp.valueOf(leaseCheckTime));
    }

    private long evidenceCreatedAt(ShortLinkRiskProfile profile) {
        return profile.evidence() == null
                ? 0L
                : StatsEvidence.number(profile.evidence().meta().get("snapshotCreatedAt"));
    }

    public Optional<ShortLinkRiskProfile> findLatest(String gid, String domain, String shortUri) {
        List<ShortLinkRiskProfile> profiles =
                jdbcTemplate.query(
                        """
                        select *
                        from t_agent_short_link_risk_profile
                        where gid = ?
                          and domain = ?
                          and short_uri = ?
                        order by profile_window_end desc, id desc
                        limit 1
                        """,
                        (rs, rowNum) -> mapProfile(rs),
                        gid,
                        domain,
                        shortUri);
        return profiles.stream().findFirst();
    }

    /** SQL scope precedes deserialization; unscoped legacy rows can never become model evidence. */
    public List<ShortLinkRiskProfile> findAuthorized(
            AuthorizedScope scope, String batchId, int limit) {
        if (scope == null
                || scope.tenantId() == null
                || scope.links().size() > 500
                || limit < 1
                || limit > 500) {
            throw new SecurityException("An authorized bounded profile scope is required");
        }
        if (scope.links().isEmpty()) return List.of();
        List<Long> ids =
                scope.links().stream()
                        .map(link -> StatsEvidence.number(link.get("linkId")))
                        .distinct()
                        .toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(scope.tenantId());
        parameters.addAll(ids);
        String batchClause = "";
        if (batchId != null && !batchId.isBlank()) {
            batchClause = " AND batch_id = ?";
            parameters.add(batchId);
        }
        parameters.add(limit);
        String sql =
                "SELECT * FROM (SELECT p.*, ROW_NUMBER() OVER(PARTITION BY link_id ORDER BY"
                    + " profile_window_end DESC, id DESC) AS profile_rank FROM"
                    + " t_agent_short_link_risk_profile p WHERE tenant_id = ? AND link_id IN ("
                        + placeholders
                        + ")"
                        + batchClause
                        + ") scoped WHERE profile_rank = 1 ORDER BY risk_score DESC, link_id LIMIT"
                        + " ?";
        List<ShortLinkRiskProfile> profiles =
                jdbcTemplate.query(sql, (rs, row) -> mapProfile(rs), parameters.toArray());
        for (ShortLinkRiskProfile profile : profiles) {
            if (profile.evidence() == null
                    || !scope.tenantId().equals(profile.evidence().tenantId())
                    || !scope.contains(profile.evidence().linkId(), null))
                throw new SecurityException("Stored profile ownership is inconsistent");
        }
        return profiles.stream()
                .map(
                        profile -> {
                            Map<String, Object> current =
                                    scope.links().stream()
                                            .filter(
                                                    link ->
                                                            StatsEvidence.number(link.get("linkId"))
                                                                    == profile.evidence().linkId())
                                            .findFirst()
                                            .orElseThrow();
                            return new ShortLinkRiskProfile(
                                    String.valueOf(current.get("gid")),
                                    String.valueOf(current.get("domain")),
                                    String.valueOf(current.get("shortUri")),
                                    String.valueOf(current.get("fullShortUrl")),
                                    profile.profileWindowStart(),
                                    profile.profileWindowEnd(),
                                    profile.metrics(),
                                    profile.anomalyScore(),
                                    profile.riskScore(),
                                    profile.riskLevel(),
                                    profile.reasonCodes(),
                                    profile.watchStatus(),
                                    profile.latestPolicyActions(),
                                    profile.latestAgentSummary(),
                                    profile.batchId(),
                                    profile.evidence());
                        })
                .toList();
    }

    public List<ShortLinkRiskProfile> findLatestByGid(String gid) {
        List<ShortLinkRiskProfile> orderedProfiles =
                jdbcTemplate.query(
                        """
                        select *
                        from t_agent_short_link_risk_profile
                        where gid = ?
                        order by profile_window_end desc, domain asc, short_uri asc, id desc
                        """,
                        (rs, rowNum) -> mapProfile(rs),
                        gid);
        Map<String, ShortLinkRiskProfile> latestByTarget = new LinkedHashMap<>();
        for (ShortLinkRiskProfile profile : orderedProfiles) {
            latestByTarget.putIfAbsent(profile.domain() + "\n" + profile.shortUri(), profile);
        }
        return new ArrayList<>(latestByTarget.values());
    }

    public List<ShortLinkRiskProfile> findByBatchIdAndGid(String batchId, String gid) {
        return jdbcTemplate.query(
                """
                select *
                from t_agent_short_link_risk_profile
                where batch_id = ?
                  and gid = ?
                order by domain asc, short_uri asc, id desc
                """,
                (rs, rowNum) -> mapProfile(rs),
                batchId,
                gid);
    }

    public Optional<ShortLinkRiskProfile> findByBatchIdAndTarget(
            String batchId, String gid, String domain, String shortUri) {
        List<ShortLinkRiskProfile> profiles =
                jdbcTemplate.query(
                        """
                        select *
                        from t_agent_short_link_risk_profile
                        where batch_id = ?
                          and gid = ?
                          and domain = ?
                          and short_uri = ?
                        order by id desc
                        limit 1
                        """,
                        (rs, rowNum) -> mapProfile(rs),
                        batchId,
                        gid,
                        domain,
                        shortUri);
        return profiles.stream().findFirst();
    }

    public List<ShortLinkRiskProfile> findTopRiskByGid(String gid, int limit) {
        return findLatestByGid(gid).stream()
                .sorted(
                        (first, second) -> {
                            int scoreCompare =
                                    Integer.compare(second.riskScore(), first.riskScore());
                            if (scoreCompare != 0) {
                                return scoreCompare;
                            }
                            int windowCompare =
                                    second.profileWindowEnd().compareTo(first.profileWindowEnd());
                            if (windowCompare != 0) {
                                return windowCompare;
                            }
                            return first.shortUri().compareTo(second.shortUri());
                        })
                .limit(Math.max(0, limit))
                .toList();
    }

    private ShortLinkRiskProfile mapProfile(ResultSet rs) throws SQLException {
        Map<?, ?> snapshot = profileSnapshot(rs.getString("profile_json"));
        ShortLinkRiskMetrics metrics =
                new ShortLinkRiskMetrics(
                        rs.getLong("pv_2h"),
                        rs.getLong("uv_2h"),
                        rs.getLong("pv_24h"),
                        rs.getLong("uv_24h"),
                        rs.getLong("pv_7d"),
                        rs.getLong("uv_7d"),
                        doubleValue(rs, "pv_growth_2h_vs_24h_avg"),
                        doubleValue(rs, "top_ip_share"),
                        doubleValue(rs, "top_visitor_share"),
                        doubleValue(rs, "top_region_share"),
                        doubleValue(rs, "top_device_share"),
                        doubleValue(rs, "top_browser_share"),
                        doubleValue(rs, "pv_per_uv"),
                        doubleValue(rs, "peak_hour_share"),
                        doubleValue(rs, "repeat_visit_ratio"));
        return new ShortLinkRiskProfile(
                rs.getString("gid"),
                rs.getString("domain"),
                rs.getString("short_uri"),
                rs.getString("full_short_url"),
                localDateTime(rs.getTimestamp("profile_window_start")),
                localDateTime(rs.getTimestamp("profile_window_end")),
                metrics,
                rs.getInt("anomaly_score"),
                rs.getInt("risk_score"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                reasonCodes(rs.getString("reason_codes_json")),
                watchStatus(snapshot),
                stringList(snapshot.get("latestPolicyActions")),
                stringValue(snapshot.get("latestAgentSummary")),
                rs.getString("batch_id"),
                StatsEvidence.from(snapshot.get("evidence")));
    }

    private List<String> reasonCodeNames(Set<RiskReasonCode> reasonCodes) {
        return reasonCodes.stream().map(RiskReasonCode::name).sorted().toList();
    }

    private Set<RiskReasonCode> reasonCodes(String reasonCodesJson) {
        String[] values = jsonCodec.fromJson(reasonCodesJson, String[].class);
        return List.of(values).stream()
                .map(RiskReasonCode::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Map<String, Object> profileSnapshot(ShortLinkRiskProfile profile) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gid", profile.gid());
        snapshot.put("domain", profile.domain());
        snapshot.put("shortUri", profile.shortUri());
        snapshot.put("fullShortUrl", profile.fullShortUrl());
        snapshot.put("profileWindowStart", profile.profileWindowStart().toString());
        snapshot.put("profileWindowEnd", profile.profileWindowEnd().toString());
        snapshot.put("metrics", profile.metrics());
        snapshot.put("anomalyScore", profile.anomalyScore());
        snapshot.put("riskScore", profile.riskScore());
        snapshot.put("riskLevel", profile.riskLevel().name());
        snapshot.put("reasonCodes", reasonCodeNames(profile.reasonCodes()));
        snapshot.put("watchStatus", profile.watchStatus().name());
        snapshot.put("latestPolicyActions", profile.latestPolicyActions());
        snapshot.put("latestAgentSummary", profile.latestAgentSummary());
        snapshot.put("batchId", profile.batchId());
        snapshot.put("evidence", profile.evidence() == null ? null : profile.evidence().toMap());
        return snapshot;
    }

    private Map<?, ?> profileSnapshot(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) {
            return Map.of();
        }
        try {
            return jsonCodec.fromJson(profileJson, Map.class);
        } catch (IllegalArgumentException ex) {
            return Map.of();
        }
    }

    private RiskWatchStatus watchStatus(Map<?, ?> snapshot) {
        String value = stringValue(snapshot.get("watchStatus"));
        if (value.isBlank()) {
            return RiskWatchStatus.NONE;
        }
        try {
            return RiskWatchStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return RiskWatchStatus.NONE;
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Double doubleValue(ResultSet rs, String columnName) throws SQLException {
        BigDecimal value = rs.getBigDecimal(columnName);
        return value == null ? null : value.doubleValue();
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
