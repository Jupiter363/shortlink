package com.jupiter.shortlink.analytics.api.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.analytics.api.ApiSettings;
import com.jupiter.shortlink.analytics.api.QueryFailure;
import com.jupiter.shortlink.analytics.api.QueryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
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
