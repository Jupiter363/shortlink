package com.jupiter.shortlink.command.security;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.security.ResourceAuthorizationController.SelectedAnalyticsRequest;
import com.jupiter.shortlink.command.security.ResourceAuthorizationController.SelectedRequest;
import com.jupiter.shortlink.contract.FrozenQueryScope;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

/** Uses actual Command authorization and route SQL; no service process or MySQL is started. */
class SelectedResourceAuthorizationTest {
    private static final String TOKEN = "selected-authorization-fixture-token-32-bytes";
    private static final String GID = "selected-group";
    private static final List<Long> AB = List.of(11L, 12L);
    private JdbcTemplate jdbc;
    private ResourceAuthorizationController controller;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("route-publication-h2.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO t_user(id,username,password) VALUES (1,'owner','fixture'),(2,'other','fixture')");
        jdbc.update("INSERT INTO t_group(tenant_id,username,gid,name) VALUES (1,'owner',?,'owned'),(2,'other',?,'other')",
                GID, GID);
        route(11);
        route(12);
        controller = new ResourceAuthorizationController(jdbc, new CommandAuthorization(jdbc, TOKEN));
    }

    @Test
    void selectedMembersAndVersionSurviveUnrelatedGroupAdditionsAcrossBothEntrypoints() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var request = json.readValue("{\"gid\":\"selected-group\",\"linkIds\":[11,12]}", SelectedRequest.class);
        var initial = controller.resolveSelected(request, principalRequest());
        assertThat(initial.schemaVersion()).isEqualTo("selected-scope/v1");
        assertThat(initial.allowed()).isTrue();
        assertThat(initial.tenantId()).isEqualTo("1");
        assertThat(initial.subjectId()).isEqualTo("owner");
        assertThat(initial.authVersion()).isEqualTo(1);
        assertThat(initial.gid()).isEqualTo(GID);
        assertThat(initial.linkIds()).containsExactly(11L, 12L);
        assertThat(initial.memberHash()).isEqualTo(FrozenQueryScope.memberHash(AB));
        assertThat(initial.ownershipVersion()).matches("[a-f0-9]{64}");
        assertThat(initial.links()).extracting(ResourceAuthorizationController.LinkIdentity::fullShortUrl)
                .containsExactly("https://s.example/000000011", "https://s.example/000000012");

        route(13);
        jdbc.update("UPDATE t_group SET revision=revision+1 WHERE tenant_id=1 AND gid=?", GID);
        var continued = controller.resolveSelected(
                new SelectedRequest(GID, AB, initial.ownershipVersion()), principalRequest());
        assertThat(continued).isEqualTo(initial);
        var analyticsRequest = json.readValue(
                "{\"tenantId\":\"1\",\"subjectId\":\"owner\",\"authVersion\":1,\"gid\":\"selected-group\",\"linkIds\":[11,12]}",
                SelectedAnalyticsRequest.class);
        var analytics = controller.analyticsSelected(analyticsRequest, serviceRequest());
        assertThat(analytics).isEqualTo(initial);
        assertThat(json.readTree(json.writeValueAsString(analytics)).has("data")).isFalse();
        assertThatThrownBy(() -> analytics.linkIds().add(13L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> analytics.links().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void removedMembersAndInvalidCurrentPrincipalsAreDeniedRatherThanShrinkingTheSelection() {
        jdbc.update("UPDATE t_link_route SET current_gid='moved-group',ownership_version=2 WHERE link_id=12");
        denied(() -> controller.resolveSelected(new SelectedRequest(GID, AB, null), principalRequest()));
        denied(() -> controller.analyticsSelected(new SelectedAnalyticsRequest("1", "owner", 1, GID, AB), serviceRequest()));
        jdbc.update("UPDATE t_link_route SET current_gid=?,route_status='DELETED' WHERE link_id=12", GID);
        denied(() -> controller.resolveSelected(new SelectedRequest(GID, AB, null), principalRequest()));
        jdbc.update("UPDATE t_link_route SET route_status='ACTIVE' WHERE link_id=12");

        denied(() -> controller.analyticsSelected(new SelectedAnalyticsRequest("2", "other", 1, GID, AB), serviceRequest()));
        denied(() -> controller.analyticsSelected(new SelectedAnalyticsRequest("1", "other", 1, GID, AB), serviceRequest()));
        denied(() -> controller.analyticsSelected(new SelectedAnalyticsRequest("1", "owner", 1, GID, AB), new MockHttpServletRequest()));
        jdbc.update("UPDATE t_user SET auth_version=2 WHERE id=1");
        denied(() -> controller.resolveSelected(new SelectedRequest(GID, AB, null), principalRequest()));
        denied(() -> controller.analyticsSelected(new SelectedAnalyticsRequest("1", "owner", 1, GID, AB), serviceRequest()));
        jdbc.update("UPDATE t_user SET disabled=TRUE WHERE id=1");
        denied(() -> controller.analyticsSelected(new SelectedAnalyticsRequest("1", "owner", 2, GID, AB), serviceRequest()));
    }

    @Test
    void emptySelectionsStayEmptyAndClosedRequestsRejectInvalidMembersOrChangedOwnership() throws Exception {
        var empty = controller.resolveSelected(new SelectedRequest(GID, List.of(), null), principalRequest());
        assertThat(empty.linkIds()).isEmpty();
        assertThat(empty.links()).isEmpty();
        assertThat(empty.memberHash()).isEqualTo(FrozenQueryScope.memberHash(List.of()));
        assertThat(controller.analyticsSelected(new SelectedAnalyticsRequest("1", "owner", 1, GID, List.of()), serviceRequest()))
                .isEqualTo(empty);
        jdbc.update("UPDATE t_group SET del_flag=1 WHERE tenant_id=1 AND gid=?", GID);
        denied(() -> controller.resolveSelected(new SelectedRequest(GID, List.of(), null), principalRequest()));
        jdbc.update("UPDATE t_group SET del_flag=0 WHERE tenant_id=1 AND gid=?", GID);

        var original = controller.resolveSelected(new SelectedRequest(GID, AB, null), principalRequest());
        jdbc.update("UPDATE t_link_route SET ownership_version=ownership_version+1 WHERE link_id=12");
        var conflict = catchThrowableOfType(
                () -> controller.resolveSelected(new SelectedRequest(GID, AB, original.ownershipVersion()), principalRequest()),
                ResponseStatusException.class);
        assertThat(conflict).isNotNull();
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        for (List<Long> invalid : Arrays.asList(null, List.of(0L), List.of(12L, 11L),
                List.of(11L, 11L), Arrays.asList(11L, null), LongStream.rangeClosed(1, 501).boxed().toList())) {
            assertThatThrownBy(() -> new SelectedRequest(GID, invalid, null)).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectMapper lenientApplicationMapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThatThrownBy(() -> lenientApplicationMapper.readValue(
                "{\"gid\":\"selected-group\",\"linkIds\":[11],\"fullShortUrl\":\"s.example/other\"}", SelectedRequest.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        for (String coerced : List.of("11.9", "\"11\"")) {
            assertThatThrownBy(() -> lenientApplicationMapper.readValue(
                    "{\"gid\":\"selected-group\",\"linkIds\":[" + coerced + "]}", SelectedRequest.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> lenientApplicationMapper.readValue(
                    "{\"tenantId\":\"1\",\"subjectId\":\"owner\",\"authVersion\":1,\"gid\":\"selected-group\",\"linkIds\":["
                            + coerced + "]}", SelectedAnalyticsRequest.class))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    private void route(long id) {
        jdbc.update("INSERT INTO t_link_route(link_id,tenant_id,current_gid,domain_norm,short_uri,origin_url,updated_at)"
                        + " VALUES (?,1,?,'s.example',?,'https://example.org/fixture',0)",
                id, GID, String.format("%09d", id));
    }

    private static MockHttpServletRequest serviceRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Token", TOKEN);
        return request;
    }

    private static MockHttpServletRequest principalRequest() {
        MockHttpServletRequest request = serviceRequest();
        request.addHeader("x-shortlink-tenant-id", "1");
        request.addHeader("x-shortlink-username", "owner");
        request.addHeader("x-shortlink-auth-version", "1");
        return request;
    }

    private static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable request) {
        var failure = catchThrowableOfType(request, ResponseStatusException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
