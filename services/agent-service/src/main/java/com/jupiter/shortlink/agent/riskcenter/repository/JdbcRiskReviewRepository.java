package com.jupiter.shortlink.agent.riskcenter.repository;

import com.jupiter.shortlink.agent.riskcenter.model.RiskReview;
import com.jupiter.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.jupiter.shortlink.agent.riskcommon.model.RiskTargetType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRiskReviewRepository {
    public java.util.Map<String, Object> latestGroupState(
            com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope
                    scope,
            String gid) {
        if (scope == null || scope.tenantId() == null || gid == null || gid.isBlank())
            throw new SecurityException("Current group scope is required");
        String sql =
                "SELECT * FROM (SELECT r.*,ROW_NUMBER() OVER(PARTITION BY CASE WHEN review_action"
                    + " IN ('WATCH','UNWATCH') THEN 'WATCH_STATE' ELSE 'ASSESSMENT' END ORDER BY"
                    + " review_time DESC,id DESC) AS state_rank FROM t_agent_risk_review r WHERE"
                    + " tenant_id=? AND gid=? AND target_type='GROUP') ranked WHERE state_rank=1";
        java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
        for (RiskReview review :
                jdbcTemplate.query(sql, (rs, row) -> mapReview(rs), scope.tenantId(), gid))
            mergeState(state, review);
        return state;
    }

    private void mergeState(java.util.Map<String, Object> state, RiskReview review) {
        if (review.reviewAction() == RiskReviewAction.WATCH
                || review.reviewAction() == RiskReviewAction.UNWATCH)
            state.put(
                    "watchStatus",
                    review.reviewAction() == RiskReviewAction.WATCH ? "WATCHING" : "NONE");
        else {
            state.put("action", review.reviewAction().name());
            state.put("reviewer", review.reviewer());
            state.put("reviewTime", review.reviewTime().toString());
            state.put("reviewNote", review.reviewNote());
        }
    }

    public java.util.Map<Long, java.util.Map<String, Object>> latestStates(
            com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient.AuthorizedScope
                    scope) {
        if (scope == null || scope.tenantId() == null || scope.links().size() > 500)
            throw new SecurityException("Current review scope is required");
        if (scope.links().isEmpty()) return java.util.Map.of();
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(scope.tenantId());
        scope.links().stream()
                .map(
                        row ->
                                com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence.number(
                                        row.get("linkId")))
                .distinct()
                .forEach(args::add);
        String sql =
                "SELECT * FROM (SELECT r.*,ROW_NUMBER() OVER(PARTITION BY link_id,CASE WHEN"
                    + " review_action IN ('WATCH','UNWATCH') THEN 'WATCH_STATE' ELSE 'ASSESSMENT'"
                    + " END ORDER BY review_time DESC,id DESC) AS state_rank FROM"
                    + " t_agent_risk_review r WHERE tenant_id=? AND link_id IN ("
                        + String.join(",", java.util.Collections.nCopies(args.size() - 1, "?"))
                        + ")) ranked WHERE state_rank=1";
        java.util.Map<Long, java.util.Map<String, Object>> result = new java.util.HashMap<>();
        for (RiskReview review :
                jdbcTemplate.query(sql, (rs, row) -> mapReview(rs), args.toArray())) {
            java.util.Map<String, Object> state =
                    result.computeIfAbsent(
                            review.linkId(), ignored -> new java.util.LinkedHashMap<>());
            mergeState(state, review);
        }
        return result;
    }

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveReview(RiskReview review) {
        jdbcTemplate.update(
                """
                insert into t_agent_risk_review (
                    tenant_id,link_id,
                    review_id,
                    event_id,
                    target_type,
                    gid,
                    domain,
                    short_uri,
                    full_short_url,
                    review_action,
                    reviewer,
                    review_note,
                    review_time
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                review.tenantId(),
                review.linkId(),
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
                Timestamp.valueOf(review.reviewTime()));
    }

    public Optional<RiskReview> findByReviewId(String reviewId) {
        List<RiskReview> reviews =
                jdbcTemplate.query(
                        """
                        select *
                        from t_agent_risk_review
                        where review_id = ?
                        """,
                        (rs, rowNum) -> mapReview(rs),
                        reviewId);
        return reviews.stream().findFirst();
    }

    public List<RiskReview> listByGid(String gid) {
        return jdbcTemplate.query(
                """
                select *
                from t_agent_risk_review
                where gid = ?
                order by review_time desc, id desc
                """,
                (rs, rowNum) -> mapReview(rs),
                gid);
    }

    private RiskReview mapReview(ResultSet rs) throws SQLException {
        return new RiskReview(
                rs.getString("review_id"),
                rs.getString("event_id"),
                RiskTargetType.valueOf(rs.getString("target_type")),
                rs.getString("gid"),
                rs.getString("domain"),
                rs.getString("short_uri"),
                rs.getString("full_short_url"),
                RiskReviewAction.valueOf(rs.getString("review_action")),
                rs.getString("reviewer"),
                rs.getString("review_note"),
                localDateTime(rs.getTimestamp("review_time")),
                rs.getString("tenant_id"),
                (Long) rs.getObject("link_id"));
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
