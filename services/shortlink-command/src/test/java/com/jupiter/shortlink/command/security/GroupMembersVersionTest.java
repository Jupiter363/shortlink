package com.jupiter.shortlink.command.security;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.batch.BatchLimits;
import com.jupiter.shortlink.command.batch.TenantQuotaService;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.membership.ExistingBusinessPublicationFixture;
import com.jupiter.shortlink.command.outbox.BusinessOutbox;
import com.jupiter.shortlink.id.IdGenerator;
import com.jupiter.shortlink.id.IdRange;
import com.jupiter.shortlink.id.ShortCodeCodec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/** Real membership mutations on H2; does not claim MySQL isolation or publication-barrier coverage. */
class GroupMembersVersionTest {
    private static final CommandPrincipal OWNER = new CommandPrincipal(1, "owner", 1);

    @Test
    void actualCreateInvalidatesPagedOwnershipOnlyWhenMemberAndGroupVersionCommitTogether() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("route-publication-h2.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        var transactions = new DataSourceTransactionManager(source);
        Clock clock = Clock.fixed(Instant.parse("2026-09-20T00:00:00Z"), ZoneOffset.UTC);
        jdbc.update("INSERT INTO t_user(id,username,password) VALUES (1,'owner','fixture-only')");
        var auth = new CommandAuthorization(jdbc, "group-members-version-test-token-32-bytes");
        var groups = new GroupCommandService(jdbc, transactions, auth, clock);
        String gid = groups.create(OWNER, "enumerated").gid();
        var limits = new BatchLimits(100000, 8, 2, 134217728, 67108864, 100000, 200, 2, 30000, 3);
        ObjectMapper json = new ObjectMapper();
        var links = new LinkCommandService(jdbc, transactions, auth, groups, ids(),
                new ShortCodeCodec(new byte[32]), new BusinessOutbox(jdbc, json, clock), json, clock,
                new TenantQuotaService(jdbc, limits), new ExistingBusinessPublicationFixture(), "s.example");
        var authorization = new ResourceAuthorizationController(jdbc, auth);

        // Both seed batches use the actual create transaction, including group reference updates.
        links.createMany(OWNER, "seed-first-page", creations(gid, 500));
        links.createMany(OWNER, "seed-last-page", creations(gid, 1));
        List<Long> originalMembers = members(jdbc, gid);
        assertThat(originalMembers).hasSize(501);
        var first = authorization.resolve(OWNER, request(gid, null, null));
        assertThat(first.links()).hasSize(500);
        assertThat(first.nextCursor()).isEqualTo(originalMembers.get(499));
        var continuation = request(gid, first.nextCursor(), first.ownershipVersion());
        long originalRevision = revision(jdbc, gid);

        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            var rolledBack = links.createMany(OWNER, "rolled-back-member", creations(gid, 1)).get(0);
            assertThat(members(jdbc, gid)).hasSize(502).contains(rolledBack.linkId());
            assertThat(revision(jdbc, gid)).isEqualTo(originalRevision + 1);
            transaction.setRollbackOnly();
        });
        assertThat(members(jdbc, gid)).containsExactlyElementsOf(originalMembers);
        assertThat(revision(jdbc, gid)).isEqualTo(originalRevision);
        var afterRollback = authorization.resolve(OWNER, continuation);
        assertThat(afterRollback.links()).extracting(ResourceAuthorizationController.LinkIdentity::linkId)
                .containsExactly(originalMembers.get(500));
        assertThat(afterRollback.ownershipVersion()).isEqualTo(first.ownershipVersion());
        assertThat(afterRollback.nextCursor()).isNull();

        var committed = links.createMany(OWNER, "committed-member", creations(gid, 1)).get(0);
        assertThat(members(jdbc, gid)).hasSize(502).contains(committed.linkId());
        assertThat(revision(jdbc, gid)).isEqualTo(originalRevision + 1);
        ResponseStatusException expired = catchThrowableOfType(
                () -> authorization.resolve(OWNER, continuation), ResponseStatusException.class);
        assertThat(expired).isNotNull();
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(authorization.resolve(OWNER, request(gid, null, null)).ownershipVersion())
                .isNotEqualTo(first.ownershipVersion());
    }

    private static List<LinkCommandService.Creation> creations(String gid, int count) {
        return IntStream.range(0, count).mapToObj(index -> new LinkCommandService.Creation(
                "s.example", "https://example.org/" + index, gid, 0, 0, null, "member " + index)).toList();
    }

    private static ResourceAuthorizationController.ResolveRequest request(String gid, Long after, String version) {
        return new ResourceAuthorizationController.ResolveRequest(gid, null, null, after, version);
    }

    private static List<Long> members(JdbcTemplate jdbc, String gid) {
        return jdbc.queryForList("SELECT link_id FROM t_link_route WHERE tenant_id=1 AND current_gid=? "
                + "AND route_status<>'DELETED' ORDER BY link_id", Long.class, gid);
    }

    private static long revision(JdbcTemplate jdbc, String gid) {
        return jdbc.queryForObject("SELECT revision FROM t_group WHERE tenant_id=1 AND username='owner' AND gid=?",
                Long.class, gid);
    }

    private static IdGenerator ids() {
        AtomicLong next = new AtomicLong(1000);
        return new IdGenerator() {
            @Override public long nextId() { return next.getAndIncrement(); }
            @Override public List<IdRange> reserveRanges(int count) {
                long first = next.getAndAdd(count);
                return List.of(new IdRange(first, first + count));
            }
            @Override public void close() {}
        };
    }
}
