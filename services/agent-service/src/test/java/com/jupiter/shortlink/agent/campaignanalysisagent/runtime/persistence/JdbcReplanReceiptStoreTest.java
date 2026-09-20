package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Caller;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunDefinition;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.RunToken;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcReplanReceiptStoreTest {
    @Test
    void recordsAndReadsAnIdempotentReceipt() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:replan_receipt;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE campaign_run_ledger (run_id VARCHAR(96),revision INT,tenant_id VARCHAR(96),subject_name VARCHAR(128),auth_version BIGINT,run_status VARCHAR(24),row_version BIGINT,advance_token VARCHAR(36),PRIMARY KEY(run_id,revision))");
        jdbc.update("INSERT INTO campaign_run_ledger VALUES ('run',1,'t','s',1,'ACTIVE',1,'advance')");
        jdbc.execute("CREATE TABLE campaign_replan_receipt (receipt_id VARCHAR(96) PRIMARY KEY,run_id VARCHAR(96),base_revision INT,candidate_revision INT,candidate_plan_hash CHAR(64),request_json CLOB,decision VARCHAR(16),reason_code VARCHAR(64),created_at BIGINT,UNIQUE(run_id,base_revision))");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        JdbcReplanReceiptStore store = new JdbcReplanReceiptStore(jdbc, tx,
                Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC));
        RunToken run = new RunToken(new RunDefinition(new Caller("t", "s", 1), "session", "run", "plan", 1, "{}"), 1, "advance");

        var first = store.record(run, 2, "a".repeat(64), "{\"x\":1}", "ACCEPTED", null);
        var second = store.record(run, 2, "a".repeat(64), "{\"x\":1}", "ACCEPTED", null);

        assertThat(second).isEqualTo(first);
        assertThat(store.find(run)).contains(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campaign_replan_receipt", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.record(run, 2, "b".repeat(64), "{\"x\":2}", "ACCEPTED", null))
                .isInstanceOf(IllegalStateException.class).hasMessage("REPLAN_RECEIPT_CONFLICT");
    }
}
