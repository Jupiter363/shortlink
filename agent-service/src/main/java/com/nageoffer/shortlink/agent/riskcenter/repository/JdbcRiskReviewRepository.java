package com.nageoffer.shortlink.agent.riskcenter.repository;

import com.nageoffer.shortlink.agent.riskcenter.model.RiskReview;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskReviewAction;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskTargetType;
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

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveReview(RiskReview review) {
        jdbcTemplate.update("""
                        insert into t_agent_risk_review (
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
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
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
                Timestamp.valueOf(review.reviewTime())
        );
    }

    public Optional<RiskReview> findByReviewId(String reviewId) {
        List<RiskReview> reviews = jdbcTemplate.query("""
                        select *
                        from t_agent_risk_review
                        where review_id = ?
                        """,
                (rs, rowNum) -> mapReview(rs),
                reviewId
        );
        return reviews.stream().findFirst();
    }

    public List<RiskReview> listByGid(String gid) {
        return jdbcTemplate.query("""
                        select *
                        from t_agent_risk_review
                        where gid = ?
                        order by review_time desc, id desc
                        """,
                (rs, rowNum) -> mapReview(rs),
                gid
        );
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
                localDateTime(rs.getTimestamp("review_time"))
        );
    }

    private LocalDateTime localDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
