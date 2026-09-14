package com.jupiter.shortlink.membership;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class MembershipFixture {
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager manager;
    final TransactionTemplate business;
    final AtomicLong clock = new AtomicLong(10_000_000_000L);
    final JdbcRouteMembershipStore store;

    MembershipFixture() {
        this(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", ""));
    }

    MembershipFixture(DriverManagerDataSource source) {
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        business = new TransactionTemplate(manager);
        store = new JdbcRouteMembershipStore(jdbc, manager, clock::get);
    }

    void createSchema() {
        jdbc.execute("CREATE TABLE t_route_membership_control (namespace VARCHAR(64) PRIMARY KEY,"
                + "generation VARCHAR(36) NOT NULL,revision BIGINT NOT NULL,member_count BIGINT NOT NULL,"
                + "mode VARCHAR(16) NOT NULL,baseline_ready BOOLEAN NOT NULL,transition_id VARCHAR(36) NOT NULL)");
        jdbc.execute("CREATE TABLE t_route_membership (namespace VARCHAR(64) NOT NULL,generation VARCHAR(36) NOT NULL,"
                + "member_ordinal BIGINT NOT NULL,registration_revision BIGINT NOT NULL,domain_norm VARCHAR(253) NOT NULL,"
                + "short_uri VARCHAR(64) NOT NULL,PRIMARY KEY(namespace,generation,domain_norm,short_uri),"
                + "UNIQUE(namespace,generation,member_ordinal))");
        jdbc.execute("CREATE TABLE t_link_route (link_id BIGINT PRIMARY KEY,domain_norm VARCHAR(253) NOT NULL,"
                + "short_uri VARCHAR(64) NOT NULL,route_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',"
                + "UNIQUE(domain_norm,short_uri))");
        jdbc.execute("CREATE TABLE test_outbox (revision BIGINT PRIMARY KEY)");
        reset();
    }

    void reset() {
        jdbc.update("DELETE FROM t_link_route");
        jdbc.update("DELETE FROM test_outbox");
        jdbc.update("DELETE FROM t_route_membership");
        jdbc.update("DELETE FROM t_route_membership_control");
        jdbc.update("INSERT INTO t_route_membership_control "
                        + "(namespace,generation,revision,member_count,mode,baseline_ready,transition_id) "
                        + "VALUES ('routes',?,0,0,'OFF',FALSE,?)",
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    void advanceGrace() { clock.addAndGet(JdbcRouteMembershipStore.PUBLICATION_DELAY_NANOS); }

    void enforceEmpty() {
        DrainPermit baseline = store.beginDrain();
        advanceGrace();
        store.completeBaseline(baseline);
        DrainPermit transition = store.beginDrain();
        advanceGrace();
        store.finishDrain(transition, Mode.ENFORCE);
    }

    static RouteAddress address(String shortUri) { return new RouteAddress("s.example", shortUri); }
}
