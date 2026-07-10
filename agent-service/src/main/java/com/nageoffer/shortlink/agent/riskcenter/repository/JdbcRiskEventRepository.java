package com.nageoffer.shortlink.agent.riskcenter.repository;

import com.nageoffer.shortlink.agent.riskcenter.model.RiskEvent;
import com.nageoffer.shortlink.agent.riskcommon.json.RiskJsonCodec;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskEventSource;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskLevel;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReasonCode;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskSensitiveDataGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcRiskEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RiskJsonCodec jsonCodec;
    private final RiskSensitiveDataGuard sensitiveDataGuard;

    @Autowired
    public JdbcRiskEventRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskJsonCodec(), new RiskSensitiveDataGuard());
    }

    public JdbcRiskEventRepository(
            JdbcTemplate jdbcTemplate,
            RiskJsonCodec jsonCodec,
            RiskSensitiveDataGuard sensitiveDataGuard
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
        this.sensitiveDataGuard = sensitiveDataGuard;
    }

    public void saveEvent(RiskEvent event) {
        String reasonCodesJson = jsonCodec.toJson(reasonCodeNames(event.reasonCodes()));
        String evidenceJson = jsonCodec.toJson(event.evidence());
        String recommendedActionsJson = jsonCodec.toJson(event.recommendedActions());
        sensitiveDataGuard.requireSafe(evidenceJson);
        sensitiveDataGuard.requireSafe(recommendedActionsJson);
        if (updateExisting(event, reasonCodesJson, evidenceJson, recommendedActionsJson) > 0) {
            return;
        }
        try {
            jdbcTemplate.update("""
                        insert into t_agent_risk_event (
                            event_id,
                            target_type,
                            gid,
                            domain,
                            short_uri,
                            full_short_url,
                            risk_score,
                            risk_level,
                            reason_codes_json,
                            evidence_json,
                            recommended_actions_json,
                            agent_summary,
                            trace_id,
                            session_id,
                            source,
                            event_time
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.eventId(),
                event.targetType().name(),
                event.gid(),
                event.domain(),
                event.shortUri(),
                event.fullShortUrl(),
                event.riskScore(),
                event.riskLevel().name(),
                reasonCodesJson,
                evidenceJson,
                recommendedActionsJson,
                event.agentSummary(),
                event.traceId(),
                event.sessionId(),
                event.source().name(),
                Timestamp.valueOf(event.eventTime())
            );
        } catch (DuplicateKeyException ex) {
            updateExisting(event, reasonCodesJson, evidenceJson, recommendedActionsJson);
        }
    }

    private int updateExisting(
            RiskEvent event,
            String reasonCodesJson,
            String evidenceJson,
            String recommendedActionsJson
    ) {
        return jdbcTemplate.update("""
                        update t_agent_risk_event
                        set target_type = ?,
                            gid = ?,
                            domain = ?,
                            short_uri = ?,
                            full_short_url = ?,
                            risk_score = ?,
                            risk_level = ?,
                            reason_codes_json = ?,
                            evidence_json = ?,
                            recommended_actions_json = ?,
                            agent_summary = ?,
                            trace_id = ?,
                            session_id = ?,
                            source = ?,
                            event_time = ?,
                            update_time = CURRENT_TIMESTAMP
                        where event_id = ?
                        """,
                event.targetType().name(),
                event.gid(),
                event.domain(),
                event.shortUri(),
                event.fullShortUrl(),
                event.riskScore(),
                event.riskLevel().name(),
                reasonCodesJson,
                evidenceJson,
                recommendedActionsJson,
                event.agentSummary(),
                event.traceId(),
                event.sessionId(),
                event.source().name(),
                Timestamp.valueOf(event.eventTime()),
                event.eventId()
        );
    }

    public Optional<RiskEvent> findByEventId(String eventId) {
        List<RiskEvent> events = jdbcTemplate.query("""
                        select *
                        from t_agent_risk_event
                        where event_id = ?
                        """,
                (rs, rowNum) -> mapEvent(rs),
                eventId
        );
        return events.stream().findFirst();
    }

    public List<RiskEvent> listEvents(String gid, RiskTargetType targetType, int pageNo, int pageSize) {
        return listEvents(gid, targetType, "", "", pageNo, pageSize);
    }

    public List<RiskEvent> listEvents(
            String gid,
            RiskTargetType targetType,
            String domain,
            String shortUri,
            int pageNo,
            int pageSize
    ) {
        QuerySpec querySpec = querySpec(gid, targetType, domain, shortUri);
        List<Object> args = new ArrayList<>(querySpec.args());
        args.add(safeLimit(pageSize));
        args.add(offset(pageNo, pageSize));
        return jdbcTemplate.query("""
                        select *
                        from t_agent_risk_event
                        %s
                        order by event_time desc, id desc
                        limit ? offset ?
                        """.formatted(querySpec.whereClause()),
                (rs, rowNum) -> mapEvent(rs),
                args.toArray()
        );
    }

    public long countEvents(String gid, RiskTargetType targetType, String domain, String shortUri) {
        QuerySpec querySpec = querySpec(gid, targetType, domain, shortUri);
        Long count = jdbcTemplate.queryForObject(
                "select count(1) from t_agent_risk_event " + querySpec.whereClause(),
                Long.class,
                querySpec.args().toArray()
        );
        return count == null ? 0L : count;
    }

    private QuerySpec querySpec(String gid, RiskTargetType targetType, String domain, String shortUri) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(gid)) {
            clauses.add("gid = ?");
            args.add(gid);
        }
        if (targetType != null) {
            clauses.add("target_type = ?");
            args.add(targetType.name());
        }
        if (StringUtils.hasText(domain)) {
            clauses.add("domain = ?");
            args.add(domain);
        }
        if (StringUtils.hasText(shortUri)) {
            clauses.add("short_uri = ?");
            args.add(shortUri);
        }
        String whereClause = clauses.isEmpty() ? "" : "where " + String.join(" and ", clauses);
        return new QuerySpec(whereClause, args);
    }

    private RiskEvent mapEvent(ResultSet rs) throws SQLException {
        return new RiskEvent(
                rs.getString("event_id"),
                RiskTargetType.valueOf(rs.getString("target_type")),
                rs.getString("gid"),
                rs.getString("domain"),
                rs.getString("short_uri"),
                rs.getString("full_short_url"),
                rs.getInt("risk_score"),
                RiskLevel.valueOf(rs.getString("risk_level")),
                reasonCodes(rs.getString("reason_codes_json")),
                evidence(rs.getString("evidence_json")),
                recommendedActions(rs.getString("recommended_actions_json")),
                rs.getString("agent_summary"),
                rs.getString("trace_id"),
                rs.getString("session_id"),
                RiskEventSource.valueOf(rs.getString("source")),
                localDateTime(rs.getTimestamp("event_time"))
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

    private Map<String, Object> evidence(String evidenceJson) {
        return jsonCodec.fromJson(evidenceJson, Map.class);
    }

    private List<String> recommendedActions(String recommendedActionsJson) {
        String[] values = jsonCodec.fromJson(recommendedActionsJson, String[].class);
        return List.of(values);
    }

    private int safeLimit(int pageSize) {
        if (pageSize <= 0) {
            return 10;
        }
        return Math.min(pageSize, 100);
    }

    private int offset(int pageNo, int pageSize) {
        return (Math.max(1, pageNo) - 1) * safeLimit(pageSize);
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record QuerySpec(String whereClause, List<Object> args) {
    }
}
