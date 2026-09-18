package com.jupiter.shortlink.analytics.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClickHouseFailureClassificationTest {
    private static final String SENSITIVE_ERROR =
            "Code: 241. SELECT tenant_id FROM private_table WHERE token='fixture-secret'; "
                    + "password=fixture-clickhouse-password";

    @ParameterizedTest
    @CsvSource({",false", ",true", "0,false", "0,true"})
    void successfulQueriesStillParseJsonRows(String exceptionCode, boolean optionalProof) throws Exception {
        var receivedQuery = new AtomicReference<String>();
        assertEquals(
                List.of(Map.of("n", 1)),
                request(200, exceptionCode, "{\"n\":1}\n", optionalProof, receivedQuery));
        assertTrue(receivedQuery.get().contains("&max_memory_usage=1073741824&"));
        assertTrue(receivedQuery.get().contains("&max_result_rows=2&result_overflow_mode=throw"));
        assertTrue(receivedQuery.get().contains(
                "&max_execution_time=" + (optionalProof ? 1 : 15) + "&"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void raisedQueryMemoryBudgetDoesNotRemoveTheMaterializedRowLimit(boolean optionalProof) {
        var receivedQuery = new AtomicReference<String>();
        QueryFailure failure = assertThrows(
                QueryFailure.class,
                () -> request(200, null, "{\"n\":1}\n{\"n\":2}\n", optionalProof, receivedQuery));

        assertEquals("TOO_LARGE", failure.code);
        assertEquals("Query exceeds the materialized snapshot budget", failure.getMessage());
        assertTrue(receivedQuery.get().contains("&max_memory_usage=1073741824&max_result_rows=2&"));
    }

    @ParameterizedTest
    @CsvSource({"200,false", "500,false", "503,false", "200,true", "500,true", "503,true"})
    void memoryHeaderUsesSafeClassificationWithoutExposingTheErrorBody(
            int status, boolean optionalProof) {
        QueryFailure failure = assertThrows(
                QueryFailure.class,
                () -> request(status, "241", SENSITIVE_ERROR, optionalProof));

        assertEquals("UNAVAILABLE", failure.code);
        assertEquals("ClickHouse memory capacity exceeded", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "500,,false", "503,,true", "500,60,false", "503,60,true",
        "500,not-a-code,false", "503,not-a-code,true"
    })
    void otherFailuresKeepTheGenericMessageEvenWhenTheBodyMentionsMemoryOrSecrets(
            int status, String exceptionCode, boolean optionalProof) {
        QueryFailure failure = assertThrows(
                QueryFailure.class,
                () -> request(status, exceptionCode, SENSITIVE_ERROR, optionalProof));

        assertEquals("UNAVAILABLE", failure.code);
        assertEquals("ClickHouse query failed", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"241,false", "241,true", "60,false", "60,true", "invalid,false", "invalid,true"})
    void anExceptionHeaderCannotBeMistakenForSuccessfulJsonRows(
            String exceptionCode, boolean optionalProof) {
        QueryFailure failure = assertThrows(
                QueryFailure.class,
                () -> request(200, exceptionCode, "{\"n\":1}\n", optionalProof));

        assertEquals("UNAVAILABLE", failure.code);
        assertEquals("241".equals(exceptionCode)
                ? "ClickHouse memory capacity exceeded"
                : "ClickHouse query failed", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "401,FORBIDDEN,History proof access denied",
        "403,FORBIDDEN,History proof access denied",
        "429,TOO_LARGE,History proof capacity exceeded"
    })
    void optionalProofStatusClassificationTakesPrecedenceOverTheMemoryHeader(
            int status, String expectedCode, String expectedMessage) {
        QueryFailure failure = assertThrows(
                QueryFailure.class,
                () -> request(status, "241", SENSITIVE_ERROR, true));

        assertEquals(expectedCode, failure.code);
        assertEquals(expectedMessage, failure.getMessage());
    }

    private List<Map<String, Object>> request(
            int status, String exceptionCode, String responseBody, boolean optionalProof)
            throws Exception {
        return request(status, exceptionCode, responseBody, optionalProof, new AtomicReference<>());
    }

    private List<Map<String, Object>> request(
            int status, String exceptionCode, String responseBody, boolean optionalProof,
            AtomicReference<String> receivedQuery) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                receivedQuery.set(exchange.getRequestURI().getRawQuery());
                exchange.getRequestBody().readAllBytes();
                if (exceptionCode != null)
                    exchange.getResponseHeaders().add("X-ClickHouse-Exception-Code", exceptionCode);
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var reader = new ClickHouseReader(
                    new ApiSettings("test-failure-token-long-enough", "http://localhost:1",
                            "http://localhost:2", url, "fixture-user",
                            "fixture-clickhouse-password", "test"),
                    new ObjectMapper());
            return optionalProof
                    ? reader.queryOptionalProof(url, "SELECT 1", 1)
                    : reader.query(url, "SELECT 1", 1);
        } finally {
            server.stop(0);
        }
    }
}
