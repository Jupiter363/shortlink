package com.jupiter.shortlink.agent.riskcenter.repository;

import com.jupiter.shortlink.agent.riskcenter.model.RiskSnapshot;
import com.jupiter.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.jupiter.shortlink.agent.riskcommon.model.RiskLevel;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.jupiter.shortlink.agent.riskcommon.model.RiskTargetType;
import com.jupiter.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.jupiter.shortlink.agent.riskcommon.safety.RiskSensitiveDataGuard;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcRiskSnapshotRepository {
    public Optional<RiskSnapshot> findAuthorized(
            com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope
                    scope,
            long linkId) {
        if (scope == null || !scope.contains(linkId, null))
            throw new SecurityException("Current resource scope is required");
        return jdbcTemplate
                .query(
                        "SELECT * FROM t_agent_risk_snapshot WHERE tenant_id=? AND link_id=? AND"
                            + " target_type='SHORT_LINK' ORDER BY last_scan_time DESC,id DESC LIMIT"
                            + " 1",
                        (rs, n) -> mapSnapshot(rs),
                        scope.tenantId(),
                        linkId)
                .stream()
                .findFirst();
    }

    private final JdbcTemplate jdbcTemplate;
    private final RiskJsonCodec jsonCodec;
    private final RiskSensitiveDataGuard sensitiveDataGuard;

    @Autowired
    public JdbcRiskSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskJsonCodec(), new RiskSensitiveDataGuard());
    }

    public JdbcRiskSnapshotRepository(
            JdbcTemplate jdbcTemplate,
            RiskJsonCodec jsonCodec,
            RiskSensitiveDataGuard sensitiveDataGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
        this.sensitiveDataGuard = sensitiveDataGuard;
    }

    public void upsertSnapshot(RiskSnapshot snapshot) {
        String reasonCodesJson = jsonCodec.toJson(reasonCodeNames(snapshot.reasonCodes()));
        String riskCardsJson = jsonCodec.toJson(snapshot.riskCards());
        sensitiveDataGuard.requireSafe(riskCardsJson);
        int updatedRows =
                jdbcTemplate.update(
                        """
                        update t_agent_risk_snapshot
                        set tenant_id=?,link_id=?,full_short_url = ?,
                            risk_score = ?,
                            risk_level = ?,
                            reason_codes_json = ?,
                            risk_cards_json = ?,
                            policy_status = 'UNKNOWN',
                            last_event_id = ?,
                            last_trace_id = ?,
                            last_scan_time = ?,
                            update_time = CURRENT_TIMESTAMP
                        where target_type = ?
                          and gid = ?
                          and domain = ?
                          and short_uri = ?
                          and last_scan_time <= ?
                        """,
                        snapshot.tenantId(),
                        snapshot.linkId(),
                        snapshot.fullShortUrl(),
                        snapshot.riskScore(),
                        snapshot.riskLevel().name(),
                        reasonCodesJson,
                        riskCardsJson,
                        snapshot.lastEventId(),
                        snapshot.lastTraceId(),
                        Timestamp.valueOf(snapshot.lastScanTime()),
                        snapshot.targetType().name(),
                        snapshot.gid(),
                        snapshot.domain(),
                        snapshot.shortUri(),
                        Timestamp.valueOf(snapshot.lastScanTime()));
        if (updatedRows > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    insert into t_agent_risk_snapshot (
                        tenant_id,link_id,
                        target_type,
                        gid,
                        domain,
                        short_uri,
                        full_short_url,
                        risk_score,
                        risk_level,
                        reason_codes_json,
                        risk_cards_json,
                        watch_status,
                        policy_status,
                        last_event_id,
                        last_trace_id,
                        last_scan_time
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    snapshot.tenantId(),
                    snapshot.linkId(),
                    snapshot.targetType().name(),
                    snapshot.gid(),
                    snapshot.domain(),
                    snapshot.shortUri(),
                    snapshot.fullShortUrl(),
                    snapshot.riskScore(),
                    snapshot.riskLevel().name(),
                    reasonCodesJson,
                    riskCardsJson,
                    snapshot.watchStatus().name(),
                    "UNKNOWN",
                    snapshot.lastEventId(),
                    snapshot.lastTraceId(),
                    Timestamp.valueOf(snapshot.lastScanTime()));
        } catch (org.springframework.dao.DuplicateKeyException staleOrConcurrentSnapshot) {
            // An existing snapshot won the insert race, or a strictly newer scan already exists.
            // Profile reads remain authoritative; never replace it with an older snapshot or erase
            // human review.
        }
    }

    public Optional<RiskSnapshot> findByTarget(
            RiskTargetType targetType, String gid, String domain, String shortUri) {
        List<RiskSnapshot> snapshots =
                jdbcTemplate.query(
                        """
                        select *
                        from t_agent_risk_snapshot
                        where target_type = ?
                          and gid = ?
                          and domain = ?
                          and short_uri = ?
                        limit 1
                        """,
                        (rs, rowNum) -> mapSnapshot(rs),
                        targetType.name(),
                        gid,
                        domain == null ? "" : domain,
                        shortUri == null ? "" : shortUri);
        return snapshots.stream().findFirst();
    }

    public List<RiskSnapshot> findByGid(String gid) {
        return jdbcTemplate.query(
                """
                select *
                from t_agent_risk_snapshot
                where gid = ?
                order by risk_score desc, update_time desc
                """,
                (rs, rowNum) -> mapSnapshot(rs),
                gid);
    }

    public void updateWatchStatus(
            RiskTargetType targetType,
            String gid,
            String domain,
            String shortUri,
            RiskWatchStatus watchStatus) {
        jdbcTemplate.update(
                """
                update t_agent_risk_snapshot
                set watch_status = ?,
                    update_time = CURRENT_TIMESTAMP
                where target_type = ?
                  and gid = ?
                  and domain = ?
                  and short_uri = ?
                """,
                watchStatus.name(),
                targetType.name(),
                gid,
                domain == null ? "" : domain,
                shortUri == null ? "" : shortUri);
    }

    public void markFalsePositive(
            RiskTargetType targetType, String gid, String domain, String shortUri) {
        jdbcTemplate.update(
                """
                update t_agent_risk_snapshot
                set risk_score = 0,
                    risk_level = ?,
                    reason_codes_json = ?,
                    update_time = CURRENT_TIMESTAMP
                where target_type = ?
                  and gid = ?
                  and domain = ?
                  and short_uri = ?
                """,
                RiskLevel.LOW.name(),
                jsonCodec.toJson(List.of()),
                targetType.name(),
                gid,
                domain == null ? "" : domain,
                shortUri == null ? "" : shortUri);
    }

    private RiskSnapshot mapSnapshot(ResultSet rs) throws SQLException {
        return new RiskSnapshot(
                RiskTargetType.valueOf(rs.getString("target_type")),
                rs.getString("gid"),
                rs.getString("domain"),
                rs.getString("short_uri"),
                rs.getString("full_short_url"),
                rs.getInt("risk_score"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                reasonCodes(rs.getString("reason_codes_json")),
                riskCards(rs.getString("risk_cards_json")),
                RiskWatchStatus.valueOf(rs.getString("watch_status")),
                rs.getString("policy_status"),
                rs.getString("last_event_id"),
                rs.getString("last_trace_id"),
                localDateTime(rs.getTimestamp("last_scan_time")));
    }

    private List<String> reasonCodeNames(List<RiskReasonCode> reasonCodes) {
        return reasonCodes.stream().map(RiskReasonCode::name).toList();
    }

    private List<RiskReasonCode> reasonCodes(String reasonCodesJson) {
        String[] values = jsonCodec.fromJson(reasonCodesJson, String[].class);
        return List.of(values).stream().map(RiskReasonCode::valueOf).toList();
    }

    private List<Map<String, Object>> riskCards(String riskCardsJson) {
        Map[] rows = jsonCodec.fromJson(riskCardsJson, Map[].class);
        return List.of(rows).stream().map(row -> (Map<String, Object>) row).toList();
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
