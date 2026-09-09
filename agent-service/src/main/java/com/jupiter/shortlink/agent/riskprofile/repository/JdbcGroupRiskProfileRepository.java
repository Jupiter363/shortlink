package com.jupiter.shortlink.agent.riskprofile.repository;

import com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope;
import com.jupiter.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.jupiter.shortlink.agent.riskcommon.model.RiskLevel;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.jupiter.shortlink.agent.riskprofile.model.GroupRiskProfile;
import com.jupiter.shortlink.agent.riskprofile.model.RiskTrendPoint;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcGroupRiskProfileRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskJsonCodec jsonCodec;

    @Autowired
    public JdbcGroupRiskProfileRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskJsonCodec());
    }

    public JdbcGroupRiskProfileRepository(JdbcTemplate jdbcTemplate, RiskJsonCodec jsonCodec) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public boolean saveIfLeaseOwned(
            GroupRiskProfile profile, String ownerToken, LocalDateTime leaseCheckTime) {
        if (ownerToken == null || ownerToken.isBlank()) {
            throw new IllegalArgumentException("ownerToken must not be blank");
        }
        if (leaseCheckTime == null) {
            throw new IllegalArgumentException("leaseCheckTime must not be null");
        }
        return saveInternal(profile, ownerToken, leaseCheckTime);
    }

    private boolean saveInternal(
            GroupRiskProfile profile, String ownerToken, LocalDateTime leaseCheckTime) {
        String reasonCodesJson = jsonCodec.toJson(reasonCodeNames(profile.groupReasonCodes()));
        String topLinksJson =
                jsonCodec.toJson(
                        profile.topRiskShortLinks().stream().map(this::shortLinkSnapshot).toList());
        String trendJson =
                jsonCodec.toJson(profile.riskTrend7d().stream().map(this::trendSnapshot).toList());
        if (updateExisting(
                        profile,
                        reasonCodesJson,
                        topLinksJson,
                        trendJson,
                        ownerToken,
                        leaseCheckTime)
                > 0) {
            return true;
        }
        try {
            int insertedRows =
                    jdbcTemplate.update(
                            """
                            insert into t_agent_group_risk_profile (
                                batch_id,
                                tenant_id,
                                evidence_created_at,
                                gid,
                                profile_window_start,
                                profile_window_end,
                                total_short_links_scanned,
                                low_risk_count,
                                medium_risk_count,
                                high_risk_count,
                                watching_count,
                                disabled_count,
                                avg_risk_score,
                                max_risk_score,
                                group_risk_score,
                                group_risk_level,
                                group_reason_codes_json,
                                top_risk_short_links_json,
                                risk_trend_7d_json,
                                agent_summary
                            )
                            select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
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
                            profile.tenantId(),
                            evidenceCreatedAt(profile),
                            profile.gid(),
                            Timestamp.valueOf(profile.profileWindowStart()),
                            Timestamp.valueOf(profile.profileWindowEnd()),
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
                            reasonCodesJson,
                            topLinksJson,
                            trendJson,
                            profile.agentSummary(),
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
                            reasonCodesJson,
                            topLinksJson,
                            trendJson,
                            ownerToken,
                            leaseCheckTime)
                    > 0;
        }
    }

    private int updateExisting(
            GroupRiskProfile profile,
            String reasonCodesJson,
            String topLinksJson,
            String trendJson,
            String ownerToken,
            LocalDateTime leaseCheckTime) {
        return jdbcTemplate.update(
                """
                update t_agent_group_risk_profile
                set tenant_id = ?, evidence_created_at = ?, profile_window_start = ?,
                    profile_window_end = ?,
                    total_short_links_scanned = ?,
                    low_risk_count = ?,
                    medium_risk_count = ?,
                    high_risk_count = ?,
                    watching_count = ?,
                    disabled_count = ?,
                    avg_risk_score = ?,
                    max_risk_score = ?,
                    group_risk_score = ?,
                    group_risk_level = ?,
                    group_reason_codes_json = ?,
                    top_risk_short_links_json = ?,
                    risk_trend_7d_json = ?,
                    agent_summary = ?,
                    update_time = CURRENT_TIMESTAMP
                where batch_id = ?
                  and gid = ?
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
                profile.tenantId(),
                evidenceCreatedAt(profile),
                Timestamp.valueOf(profile.profileWindowStart()),
                Timestamp.valueOf(profile.profileWindowEnd()),
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
                reasonCodesJson,
                topLinksJson,
                trendJson,
                profile.agentSummary(),
                profile.batchId(),
                profile.gid(),
                Timestamp.valueOf(profile.profileWindowEnd()),
                evidenceCreatedAt(profile),
                profile.batchId(),
                ownerToken,
                com.jupiter.shortlink.agent.riskprofile.batch.RiskProfileBatchStatus.RUNNING.name(),
                Timestamp.valueOf(leaseCheckTime));
    }

    private long evidenceCreatedAt(GroupRiskProfile profile) {
        return profile.topRiskShortLinks().stream()
                .filter(link -> link.evidence() != null)
                .mapToLong(
                        link ->
                                com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence.number(
                                        link.evidence().meta().get("snapshotCreatedAt")))
                .min()
                .orElse(0L);
    }

    public Optional<GroupRiskProfile> findLatestByGid(String gid) {
        List<GroupRiskProfile> profiles =
                jdbcTemplate.query(
                        """
                        select *
                        from t_agent_group_risk_profile
                        where gid = ?
                        order by profile_window_end desc, id desc
                        limit 1
                        """,
                        (rs, rowNum) -> mapProfile(rs),
                        gid);
        return profiles.stream().findFirst();
    }

    public Optional<GroupRiskProfile> findAuthorized(
            AuthorizedScope scope, String gid, String batchId) {
        if (scope == null
                || scope.tenantId() == null
                || gid == null
                || scope.links().size() > 500) {
            throw new SecurityException("Current group profile scope is required");
        }
        if (scope.links().isEmpty()) return Optional.empty();
        String sql = "SELECT * FROM t_agent_group_risk_profile WHERE tenant_id = ? AND gid = ?";
        java.util.List<Object> parameters =
                new java.util.ArrayList<>(java.util.List.of(scope.tenantId(), gid));
        if (batchId != null && !batchId.isBlank()) {
            sql += " AND batch_id = ?";
            parameters.add(batchId);
        }
        sql += " ORDER BY profile_window_end DESC, id DESC LIMIT 1";
        Optional<GroupRiskProfile> result =
                jdbcTemplate
                        .query(
                                sql,
                                (rs, row) -> {
                                    Map[] members =
                                            jsonCodec.fromJson(
                                                    rs.getString("top_risk_short_links_json"),
                                                    Map[].class);
                                    if (members.length == 0)
                                        throw new SecurityException(
                                                "Group profile has no verifiable membership");
                                    for (Map member : members) {
                                        var evidence =
                                                com.jupiter.shortlink.agent.riskprofile.model
                                                        .StatsEvidence.from(member.get("evidence"));
                                        if (evidence == null
                                                || !scope.tenantId().equals(evidence.tenantId())
                                                || !scope.contains(evidence.linkId(), gid))
                                            throw new SecurityException(
                                                    "Group profile membership is no longer"
                                                        + " authorized");
                                    }
                                    return mapProfile(rs);
                                },
                                parameters.toArray())
                        .stream()
                        .findFirst();
        result.ifPresent(
                profile -> {
                    for (var link : profile.topRiskShortLinks()) {
                        if (link.evidence() == null
                                || !scope.tenantId().equals(link.evidence().tenantId())
                                || !scope.contains(link.evidence().linkId(), gid))
                            throw new SecurityException(
                                    "Group profile membership is no longer authorized");
                    }
                });
        return result;
    }

    public Optional<GroupRiskProfile> findByBatchIdAndGid(String batchId, String gid) {
        List<GroupRiskProfile> profiles =
                jdbcTemplate.query(
                        """
                        select *
                        from t_agent_group_risk_profile
                        where batch_id = ?
                          and gid = ?
                        order by id desc
                        limit 1
                        """,
                        (rs, rowNum) -> mapProfile(rs),
                        batchId,
                        gid);
        return profiles.stream().findFirst();
    }

    public List<RiskTrendPoint> findTrend7d(String gid, LocalDate endDate) {
        LocalDate startDate = endDate.minusDays(6);
        List<RiskTrendPoint> points =
                jdbcTemplate.query(
                        """
                        select profile_window_end,
                               group_risk_score,
                               group_risk_level
                        from t_agent_group_risk_profile
                        where gid = ?
                          and profile_window_end >= ?
                          and profile_window_end < ?
                        order by profile_window_end asc, id asc
                        """,
                        (rs, rowNum) ->
                                new RiskTrendPoint(
                                        localDateTime(rs.getTimestamp("profile_window_end"))
                                                .toLocalDate(),
                                        rs.getInt("group_risk_score"),
                                        RiskLevel.valueOf(rs.getString("group_risk_level"))),
                        gid,
                        Timestamp.valueOf(startDate.atStartOfDay()),
                        Timestamp.valueOf(endDate.plusDays(1).atStartOfDay()));
        Map<LocalDate, RiskTrendPoint> latestByDate = new LinkedHashMap<>();
        for (RiskTrendPoint point : points) {
            latestByDate.put(point.date(), point);
        }
        return List.copyOf(latestByDate.values());
    }

    public void updateAgentSummary(String batchId, String gid, String agentSummary) {
        jdbcTemplate.update(
                """
                update t_agent_group_risk_profile
                set agent_summary = ?,
                    update_time = CURRENT_TIMESTAMP
                where batch_id = ?
                  and gid = ?
                """,
                agentSummary == null ? "" : agentSummary,
                batchId,
                gid);
    }

    private GroupRiskProfile mapProfile(ResultSet rs) throws SQLException {
        return new GroupRiskProfile(
                rs.getString("gid"),
                localDateTime(rs.getTimestamp("profile_window_start")),
                localDateTime(rs.getTimestamp("profile_window_end")),
                rs.getInt("total_short_links_scanned"),
                rs.getInt("low_risk_count"),
                rs.getInt("medium_risk_count"),
                rs.getInt("high_risk_count"),
                rs.getInt("watching_count"),
                rs.getInt("disabled_count"),
                doubleValue(rs, "avg_risk_score"),
                rs.getInt("max_risk_score"),
                rs.getInt("group_risk_score"),
                RiskLevel.valueOf(rs.getString("group_risk_level")),
                reasonCodes(rs.getString("group_reason_codes_json")),
                List.of(),
                trendPoints(rs.getString("risk_trend_7d_json")),
                rs.getString("agent_summary"),
                rs.getString("batch_id"));
    }

    private List<String> reasonCodeNames(List<RiskReasonCode> reasonCodes) {
        return reasonCodes.stream().map(RiskReasonCode::name).toList();
    }

    private List<RiskReasonCode> reasonCodes(String reasonCodesJson) {
        String[] values = jsonCodec.fromJson(reasonCodesJson, String[].class);
        return List.of(values).stream().map(RiskReasonCode::valueOf).toList();
    }

    private List<RiskTrendPoint> trendPoints(String trendJson) {
        Map[] rows = jsonCodec.fromJson(trendJson, Map[].class);
        return List.of(rows).stream()
                .map(
                        row ->
                                new RiskTrendPoint(
                                        LocalDate.parse(String.valueOf(row.get("date"))),
                                        intValue(row.get("riskScore")),
                                        RiskLevel.valueOf(String.valueOf(row.get("riskLevel")))))
                .toList();
    }

    private Map<String, Object> shortLinkSnapshot(
            com.jupiter.shortlink.agent.riskprofile.model.ShortLinkRiskProfile profile) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gid", profile.gid());
        snapshot.put("evidence", profile.evidence() == null ? null : profile.evidence().toMap());
        snapshot.put("domain", profile.domain());
        snapshot.put("shortUri", profile.shortUri());
        snapshot.put("fullShortUrl", profile.fullShortUrl());
        snapshot.put("profileWindowStart", profile.profileWindowStart().toString());
        snapshot.put("profileWindowEnd", profile.profileWindowEnd().toString());
        snapshot.put("riskScore", profile.riskScore());
        snapshot.put("riskLevel", profile.riskLevel().name());
        snapshot.put(
                "reasonCodes",
                profile.reasonCodes().stream().map(RiskReasonCode::name).sorted().toList());
        return snapshot;
    }

    private Map<String, Object> trendSnapshot(RiskTrendPoint point) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("date", point.date().toString());
        snapshot.put("riskScore", point.riskScore());
        snapshot.put("riskLevel", point.riskLevel().name());
        return snapshot;
    }

    private double doubleValue(ResultSet rs, String columnName) throws SQLException {
        BigDecimal value = rs.getBigDecimal(columnName);
        return value == null ? 0D : value.doubleValue();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
