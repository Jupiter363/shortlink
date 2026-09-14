package com.jupiter.shortlink.command.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.command.batch.*;
import com.jupiter.shortlink.command.group.GroupCommandService;
import com.jupiter.shortlink.command.link.LinkCommandService;
import com.jupiter.shortlink.command.security.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

class LinkCommandAsyncWireTest {
    private final CommandPrincipal principal = new CommandPrincipal(1, "owner", 1);
    private final LinkCommandService links = mock(LinkCommandService.class);
    private final CommandAuthorization auth = mock(CommandAuthorization.class);

    @Test
    void singleCreateUsesServletAsyncAndPreservesSuccessShape() throws Exception {
        when(auth.principal(any())).thenReturn(principal);
        CompletableFuture<List<LinkCommandService.Created>> work = new CompletableFuture<>();
        when(links.createManyAsync(eq(principal), eq("single"), anyList())).thenReturn(work);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new LinkCommandController(
                                        links,
                                        mock(GroupCommandService.class),
                                        auth,
                                        mock(JdbcTemplate.class),
                                        "s.example"))
                        .build();
        var request =
                mvc.perform(
                                post("/api/short-link/v1/create")
                                        .contentType("application/json")
                                        .content(
                                                "{\"requestId\":\"single\",\"originUrl\":\"https://example.org\",\"gid\":\"g\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        work.complete(
                List.of(
                        new LinkCommandService.Created(
                                1,
                                "https://s.example/Ab123xy90",
                                "https://example.org",
                                "g",
                                "Ab123xy90",
                                1,
                                1)));
        mvc.perform(asyncDispatch(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.fullShortUrl").value("https://s.example/Ab123xy90"));
        verify(links, never()).createMany(any(), anyString(), anyList());
    }

    @Test
    void synchronousBatchWaitsAsynchronouslyAndPreservesThe200Contract() throws Exception {
        when(auth.principal(any())).thenReturn(principal);
        CompletableFuture<List<LinkCommandService.Created>> work = new CompletableFuture<>();
        when(links.createManyAsync(eq(principal), eq("batch"), anyList())).thenReturn(work);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new BatchCompatibilityController(
                                        mock(BatchJobService.class),
                                        links,
                                        auth,
                                        new ObjectMapper(),
                                        "s.example"))
                        .build();
        var request =
                mvc.perform(
                                post("/api/short-link/v1/create/batch")
                                        .contentType("application/json")
                                        .content(
                                                "{\"requestId\":\"batch\",\"originUrls\":[\"https://example.org/1\",\"https://example.org/2\"],\"gid\":\"g\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        work.complete(
                List.of(
                        new LinkCommandService.Created(
                                1,
                                "https://s.example/Ab123xy90",
                                "https://example.org/1",
                                "g",
                                "Ab123xy90",
                                1,
                                1),
                        new LinkCommandService.Created(
                                2,
                                "https://s.example/Cd123xy90",
                                "https://example.org/2",
                                "g",
                                "Cd123xy90",
                                1,
                                1)));
        mvc.perform(asyncDispatch(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.baseLinkInfos.length()").value(2));
    }

    @Test
    void PublicationTimeoutRemainsAControlled503() throws Exception {
        when(auth.principal(any())).thenReturn(principal);
        CompletableFuture<List<LinkCommandService.Created>> work = new CompletableFuture<>();
        when(links.createManyAsync(any(), anyString(), anyList())).thenReturn(work);
        var mvc =
                MockMvcBuilders.standaloneSetup(
                                new LinkCommandController(
                                        links,
                                        mock(GroupCommandService.class),
                                        auth,
                                        mock(JdbcTemplate.class),
                                        "s.example"))
                        .build();
        var request =
                mvc.perform(
                                post("/api/short-link/v1/create")
                                        .contentType("application/json")
                                        .content(
                                                "{\"requestId\":\"timeout\",\"originUrl\":\"https://example.org\",\"gid\":\"g\"}"))
                        .andExpect(request().asyncStarted())
                        .andReturn();
        work.completeExceptionally(
                new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Route publication deadline exceeded"));
        mvc.perform(asyncDispatch(request)).andExpect(status().isServiceUnavailable());
    }
}
