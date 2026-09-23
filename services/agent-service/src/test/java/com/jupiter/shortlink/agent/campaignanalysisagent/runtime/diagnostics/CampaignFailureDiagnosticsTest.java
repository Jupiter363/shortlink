package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class CampaignFailureDiagnosticsTest {
    @Test void retainsFailureLocationAndControlledCodesWithoutProviderOrSqlContent() {
        var sql = new SQLException("SELECT question_text; secret-api-key user-prompt", "42000", 1064);
        var failure = new IllegalStateException("PLANNER_MODEL_BOUNDARY_REJECTED", sql);
        String diagnostic = CampaignFailureDiagnostics.describe(failure);
        assertTrue(diagnostic.contains("PLANNER_MODEL_BOUNDARY_REJECTED"));
        assertTrue(diagnostic.contains("java.sql.SQLException"));
        assertTrue(diagnostic.contains("sqlState=42000 vendorCode=1064"));
        assertTrue(diagnostic.contains("CampaignFailureDiagnosticsTest.retainsFailureLocation"));
        assertFalse(diagnostic.contains("SELECT"));
        assertFalse(diagnostic.contains("question_text"));
        assertFalse(diagnostic.contains("secret-api-key"));
        assertFalse(diagnostic.contains("user-prompt"));
        // A syntactically code-like provider message is still not an approved reason.
        assertFalse(CampaignFailureDiagnostics.describe(new IllegalArgumentException("MODEL_SECRET_VALUE"))
                .contains("MODEL_SECRET_VALUE"));
    }
}
