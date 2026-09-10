package com.jupiter.shortlink.admin.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jupiter.shortlink.admin.common.biz.user.*;
import com.jupiter.shortlink.admin.remote.BatchCommandRemoteService;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

class BatchExportControllerTest {
    private final BatchCommandRemoteService remote = mock(BatchCommandRemoteService.class);
    private final UserInfoDTO identity = new UserInfoDTO("1001", "zhangsan", null, 7L);
    private BatchExportController controller =
            new BatchExportController(
                    remote,
                    new ObjectMapper(),
                    "http://127.0.0.1:1",
                    "export-test-token-at-least-32-characters");

    @BeforeEach
    void login() {
        UserContext.setUser(identity);
    }

    @AfterEach
    void cleanup() {
        UserContext.removeUser();
    }

    private BatchCommandRemoteService.Status state(String state) {
        return new BatchCommandRemoteService.Status(
                "job", state, 501, 500, 1, 499, 1, null, "checksum", 120L);
    }

    @Test
    void runningJobRejectedBeforeDownloadHeadersOrRows() {
        when(remote.status("job")).thenReturn(state("RUNNING"));
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> controller.job("job", response)).hasMessageContaining("409");
        assertThat(response.getHeader("Content-Disposition")).isNull();
        verify(remote, never()).results(anyString(), anyLong(), anyInt());
    }

    @Test
    void pageStreamingPreservesStatusesAndEscapesCsv() throws Exception {
        when(remote.status("job")).thenReturn(state("PARTIAL_SUCCESS"));
        when(remote.results("job", 0, 500))
                .thenReturn(
                        List.of(
                                new BatchCommandRemoteService.Row(
                                        1,
                                        "SUCCESS",
                                        4000000001L,
                                        Map.of(
                                                "fullShortUrl",
                                                "https://s/a",
                                                "originUrl",
                                                "https://x/\"a\",b\nc"),
                                        null)));
        when(remote.results("job", 1, 500))
                .thenReturn(
                        List.of(
                                new BatchCommandRemoteService.Row(
                                        2, "CANCELLED", null, null, "  =HYPERLINK(\"evil\")")));
        when(remote.results("job", 2, 500)).thenReturn(List.of());
        var response = new MockHttpServletResponse();
        controller.job("job", response);
        String csv = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv)
                .contains(
                        "4000000001",
                        "\"CANCELLED\"",
                        "\"'  =HYPERLINK(\"\"evil\"\")\"",
                        "https://x/\"\"a\"\",b\nc");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store, private");
        assertThat(response.getHeader("Content-Disposition")).contains("batch-results.csv");
        verify(remote).results("job", 2, 500);
    }

    @Test
    void explicitIdentityOnDifferentThreadAndCleanupEvenWhenAuthorizationRevoked()
            throws Exception {
        when(remote.status("job"))
                .thenAnswer(
                        i -> {
                            assertThat(UserContext.getUserId()).isEqualTo("1001");
                            assertThat(UserContext.getAuthVersion()).isEqualTo(7L);
                            return state("CANCELLED");
                        });
        when(remote.results("job", 0, 500))
                .thenAnswer(
                        i -> {
                            assertThat(UserContext.getUsername()).isEqualTo("zhangsan");
                            throw new IllegalStateException("account version revoked");
                        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(
                            () -> {
                                UserContext.removeUser();
                                assertThatThrownBy(
                                                () ->
                                                        controller.stream(
                                                                "job",
                                                                state("CANCELLED"),
                                                                identity,
                                                                new ByteArrayOutputStream()))
                                        .hasMessageContaining("revoked");
                                assertThat(UserContext.getUserId()).isNull();
                            })
                    .get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cursorRegressionCannotLoopOrDuplicateRows() {
        when(remote.status("job")).thenReturn(state("FAILED"));
        var row = new BatchCommandRemoteService.Row(1, "FAILED", null, null, "INVALID_URL");
        when(remote.results("job", 0, 500)).thenReturn(List.of(row));
        when(remote.results("job", 1, 500)).thenReturn(List.of(row));
        assertThatThrownBy(() -> controller.job("job", new MockHttpServletResponse()))
                .hasMessageContaining("cursor");
        verify(remote, times(2)).results(anyString(), anyLong(), eq(500));
    }

    @Test
    void spreadsheetFormulaAndNewlineProtection() {
        assertThat(BatchExportController.safe("\t=1+1")).isEqualTo("'\t=1+1");
        assertThat(BatchExportController.safe("+cmd")).isEqualTo("'+cmd");
        assertThat(BatchExportController.csv("a,b", "x\"y", "line\nnext"))
                .isEqualTo("\"a,b\",\"x\"\"y\",\"line\nnext\"\r\n");
    }

    @Test
    void missingIdentityCannotExport() {
        UserContext.removeUser();
        assertThatThrownBy(() -> controller.job("job", new MockHttpServletResponse()))
                .hasMessageContaining("401");
        verifyNoInteractions(remote);
    }

    @Test
    void loopbackCommittedReceiptUsesGetAndTrustedIdentityAndProducesXlsx() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext(
                "/internal/command/creates/result",
                exchange -> {
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    assertThat(exchange.getRequestURI().getRawQuery())
                            .isEqualTo("requestId=req%2B1");
                    assertThat(exchange.getRequestHeaders().getFirst("x-shortlink-tenant-id"))
                            .isEqualTo("1001");
                    assertThat(exchange.getRequestHeaders().getFirst("x-shortlink-auth-version"))
                            .isEqualTo("7");
                    calls.incrementAndGet();
                    byte[] body =
                            "[{\"linkId\":4000000001,\"fullShortUrl\":\"https://s/a\",\"originUrl\":\"https://x/a\",\"gid\":\"g1\",\"shortUri\":\"a\",\"routeVersion\":1,\"targetRevision\":1}]"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            controller =
                    new BatchExportController(
                            remote,
                            new ObjectMapper(),
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "export-test-token-at-least-32-characters");
            var response = new MockHttpServletResponse();
            controller.committed("req+1", response);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(response.getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
            try (var workbook =
                    new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                            new ByteArrayInputStream(response.getContentAsByteArray()))) {
                assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue())
                        .isEqualTo("4000000001");
            }
            verifyNoInteractions(remote);
        } finally {
            server.stop(0);
        }
    }
}
