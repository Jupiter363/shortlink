package com.jupiter.shortlink.analytics.api.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.ApiSettings;
import com.jupiter.shortlink.analytics.api.QueryFailure;
import com.jupiter.shortlink.analytics.api.QueryRequest;
import com.jupiter.shortlink.contract.FrozenQueryScope;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QueryJobRecoveryControllerTest {
    private static final String TOKEN = "test-token-long-enough-for-analytics";
    private final ObjectMapper json = new ObjectMapper();
    private QueryJobService jobs;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        jobs = mock(QueryJobService.class);
        var settings = new ApiSettings(TOKEN, "http://localhost:1", "http://localhost:2",
                "http://localhost:8123", "default", "", "test");
        mvc = MockMvcBuilders.standaloneSetup(new QueryJobController(jobs, settings)).build();
    }

    @Test
    void dedicatedRecoveryRouteCallsOnlyRecoveryAndPreservesStatusShape() throws Exception {
        var request = request();
        when(jobs.recoverExisting(request)).thenReturn(
                new QueryJobService.Status("existing-job", "RUNNING", 0, 0, 0, null, 123456789));

        mvc.perform(post("/internal/analytics/v1/jobs/recover-existing")
                        .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.jobId").value("existing-job"))
                .andExpect(jsonPath("$.data.state").value("RUNNING"))
                .andExpect(jsonPath("$.data.expiresAt").value(123456789));
        verify(jobs).recoverExisting(request);
        verifyNoMoreInteractions(jobs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REPLAY_UNAVAILABLE", "CONFLICT", "FORBIDDEN"})
    void recoveryReturnsTheStructuredFailureWithoutFallingBackToSubmit(String code) throws Exception {
        var request = request();
        when(jobs.recoverExisting(request)).thenThrow(new QueryFailure(code, "Recovery refused"));

        mvc.perform(post("/internal/analytics/v1/jobs/recover-existing")
                        .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().is(code.equals("FORBIDDEN") ? 403 : 200))
                .andExpect(jsonPath("$.code").value(code));
        verify(jobs).recoverExisting(request);
        verifyNoMoreInteractions(jobs);
    }

    @Test
    void recoveryRequiresTheInternalTokenBeforeCallingTheService() throws Exception {
        mvc.perform(post("/internal/analytics/v1/jobs/recover-existing")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(jobs);
    }

    @Test
    void springJacksonBindsFrozenScopeWithoutRelaxingItsClosedRawValueContract() throws Exception {
        var springJson = Jackson2ObjectMapperBuilder.json().modulesToInstall(new ParameterNamesModule()).build();
        var converter = new MappingJackson2HttpMessageConverter(springJson);
        var settings = new ApiSettings(TOKEN, "http://localhost:1", "http://localhost:2",
                "http://localhost:8123", "default", "", "test");
        var springMvc = MockMvcBuilders.standaloneSetup(new QueryJobController(jobs, settings))
                .setMessageConverters(converter).build();
        var ids = List.of(4000000001L);
        var hash = FrozenQueryScope.memberHash(ids);
        var scope = new FrozenQueryScope(FrozenQueryScope.SCHEMA, "FROZEN_SET", "scope-http", hash, 1,
                "a".repeat(64), FrozenQueryScope.shardIdFor("scope-http", 0, hash), 0, 1, hash, ids);
        var request = new QueryJobService.Submit("frozen-http-request", new QueryRequest("1", "alice", 8, "g1",
                ids, 300000L, 600000L, null, "REQUESTED", null, null, 500, "LINK_METRICS", null, null, scope));
        var body = springJson.writeValueAsString(request);
        assertEquals(request, springJson.readValue(body, QueryJobService.Submit.class));
        when(jobs.submitFrozen(request)).thenReturn(new QueryJobService.Status("frozen-job", "QUEUED", 0, 0, 0, null, 123456789));
        springMvc.perform(post("/internal/analytics/v1/jobs/frozen")
                        .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.jobId").value("frozen-job"));
        verify(jobs).submitFrozen(request);
        clearInvocations(jobs);
        for (String mutation : List.of("unknown", "missing", "decimal", "string", "schema")) {
            ObjectNode invalid = (ObjectNode) springJson.readTree(body);
            ObjectNode frozen = (ObjectNode) invalid.path("query").path("scope");
            switch (mutation) {
                case "unknown" -> frozen.put("expandGroup", true);
                case "missing" -> frozen.remove("shardIndex");
                case "decimal" -> frozen.putArray("linkIds").add(4000000001.0);
                case "string" -> frozen.putArray("linkIds").add("4000000001");
                case "schema" -> frozen.put("schemaVersion", "frozen-query-scope/unsupported");
            }
            springMvc.perform(post("/internal/analytics/v1/jobs/frozen")
                            .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(springJson.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_QUERY"))
                    .andExpect(result -> assertInstanceOf(HttpMessageNotReadableException.class, result.getResolvedException()));
        }
        verifyNoInteractions(jobs);
    }

    @Test
    void originalSubmitRouteStillUsesCreateOrFind() throws Exception {
        var request = request();
        when(jobs.submit(request)).thenReturn(new QueryJobService.Status("new-job", "QUEUED", 0, 0, 0, null, 123456789));
        mvc.perform(post("/internal/analytics/v1/jobs")
                        .header("X-Internal-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobId").value("new-job"));
        verify(jobs).submit(request);
        verifyNoMoreInteractions(jobs);
    }

    private QueryJobService.Submit request() {
        return new QueryJobService.Submit("frozen-request", new QueryRequest("1", "alice", 8, "g1",
                List.of(4000000001L), 300000L, 600000L, null, "REQUESTED", null, null, 500, "METRICS"));
    }
}
