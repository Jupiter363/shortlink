package com.nageoffer.shortlink.agent.riskcenter.repository;

import com.nageoffer.shortlink.agent.riskcenter.model.RiskSnapshot;
import com.nageoffer.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskWatchStatus;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskSensitiveDataGuard;
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
            RiskSensitiveDataGuard sensitiveDataGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
        this.sensitiveDataGuard = sensitiveDataGuard;
    }

    public void upsertSnapshot(RiskSnapshot snapshot) {
        String reasonCodesJson = jsonCodec.toJson(reasonCodeNames(snapshot.reasonCodes()));
        String riskCardsJson = jsonCodec.toJson(snapshot.riskCards());
        sensitiveDataGuard.requireSafe(riskCardsJson);
        int updatedRows = jdbcTemplate.update("""
                        update t_agent_risk_snapshot
                        set full_short_url = ?,
                            risk_score = ?,
                            risk_level = ?,
                            reason_codes_json = ?,
                            risk_cards_json = ?,
                            watch_status = ?,
                            policy_status = ?,
                            last_event_id = ?,
                            last_trace_id = ?,
                            last_scan_time = ?,
                            update_time = CURRENT_TIMESTAMP
                        where target_type = ?
                          and gid = ?
                          and domain = ?
                          and short_uri = ?
                        """,
                snapshot.fullShortUrl(),
                snapshot.riskScore(),
                snapshot.riskLevel().name(),
                reasonCodesJson,
                riskCardsJson,
                snapshot.watchStatus().name(),
                snapshot.policyStatus(),
                snapshot.lastEventId(),
                snapshot.lastTraceId(),
                Timestamp.valueOf(snapshot.lastScanTime()),
                snapshot.targetType().name(),
                snapshot.gid(),
                snapshot.domain(),
                snapshot.shortUri()
        );
        if (updatedRows > 0) {
            return;
        }
        jdbcTemplate.update("""
                        insert into t_agent_risk_snapshot (
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
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
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
                snapshot.policyStatus(),
                snapshot.lastEventId(),
                snapshot.lastTraceId(),
                Timestamp.valueOf(snapshot.lastScanTime())
        );
    }

    public Optional<RiskSnapshot> findByTarget(RiskTargetType targetType, String gid, String domain, String shortUri) {
        List<RiskSnapshot> snapshots = jdbcTemplate.query("""
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
                shortUri == null ? "" : shortUri
        );
        return snapshots.stream().findFirst();
    }

    public List<RiskSnapshot> findByGid(String gid) {
        return jdbcTemplate.query("""
                        select *
                        from t_agent_risk_snapshot
                        where gid = ?
                        order by risk_score desc, update_time desc
                        """,
                (rs, rowNum) -> mapSnapshot(rs),
                gid
        );
    }

    public void updateWatchStatus(RiskTargetType targetType, String gid, String domain, String shortUri, RiskWatchStatus watchStatus) {
        jdbcTemplate.update("""
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
                shortUri == null ? "" : shortUri
        );
    }

    public void markFalsePositive(RiskTargetType targetType, String gid, String domain, String shortUri) {
        jdbcTemplate.update("""
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
                shortUri == null ? "" : shortUri
        );
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
                localDateTime(rs.getTimestamp("last_scan_time"))
        );
    }

    private List<String> reasonCodeNames(List<RiskReasonCode> reasonCodes) {
        return reasonCodes.stream()
                .map(RiskReasonCode::name)
                .toList();
    }

    private List<RiskReasonCode> reasonCodes(String reasonCodesJson) {
        String[] values = jsonCodec.fromJson(reasonCodesJson, String[].class);
        return List.of(values).stream()
                .map(RiskReasonCode::valueOf)
                .toList();
    }

    private List<Map<String, Object>> riskCards(String riskCardsJson) {
        Map[] rows = jsonCodec.fromJson(riskCardsJson, Map[].class);
        return List.of(rows).stream()
                .map(row -> (Map<String, Object>) row)
                .toList();
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
