package com.jupiter.shortlink.agent.infrastructure.llm;

import com.jupiter.shortlink.agent.infrastructure.config.DeepSeekProperties;
import com.jupiter.shortlink.agent.infrastructure.config.SpringAiChatConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/** Uses the production transport configuration and real converters; the network is a response fixture. */
class DeepSeekTransportBoundaryTest {
    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"OK", "SERVICE_UNAVAILABLE"})
    void responseWithoutContentLengthStopsAtTheLimitBeforeSuccessOrErrorBodyAllocation(HttpStatus status) {
        var fixture = fixture(1024, 128);
        var response = response(answer("x".repeat(200_000)), status);
        fixture.server.expect(requestTo("https://api.deepseek.com/chat/completions")).andRespond(request -> response);

        assertThatThrownBy(() -> fixture.model.call(new Prompt("Analyze")))
                .isInstanceOf(LlmChatClientException.class)
                .hasStackTraceContaining("configured byte limit");

        assertThat(response.body.consumed()).isLessThanOrEqualTo(1025);
        assertThat(response.body.closed).isTrue();
        assertThat(response.closed).isTrue();
        fixture.server.verify();
    }

    @Test
    void advertisedOversizeIsRejectedWithoutReadingTheBody() {
        var fixture = fixture(1024, 128);
        var response = response(answer("small"), HttpStatus.OK);
        response.getHeaders().setContentLength(10_000_000);
        fixture.server.expect(requestTo("https://api.deepseek.com/chat/completions")).andRespond(request -> response);

        assertThatThrownBy(() -> fixture.model.call(new Prompt("Analyze")))
                .isInstanceOf(LlmChatClientException.class).hasMessageContaining("configured byte limit");
        assertThat(response.body.consumed()).isZero();
        assertThat(response.body.closed).isTrue();
        assertThat(response.closed).isTrue();
        fixture.server.verify();
    }

    @Test
    void completeAnswerAtTheByteBoundaryIsAcceptedWithoutTruncatingUnicode() {
        String json = answer("保留完整分析，不截断。");
        var fixture = fixture(json.getBytes(StandardCharsets.UTF_8).length, 128);
        var response = response(json, HttpStatus.OK);
        fixture.server.expect(requestTo("https://api.deepseek.com/chat/completions")).andRespond(request -> response);

        assertThat(fixture.model.call(new Prompt("Analyze")).getResult().getOutput().getText())
                .isEqualTo("保留完整分析，不截断。");
        assertThat(response.body.closed).isTrue();
        fixture.server.verify();
    }

    @Test
    void deeplyNestedIgnoredProviderFieldsAreRejectedDuringParsing() {
        var fixture = fixture(16_384, 8);
        String json = "{\"ignored\":" + "[".repeat(20) + "0" + "]".repeat(20) + "," + answer("ready").substring(1);
        var response = response(json, HttpStatus.OK);
        fixture.server.expect(requestTo("https://api.deepseek.com/chat/completions")).andRespond(request -> response);

        assertThatThrownBy(() -> fixture.model.call(new Prompt("Analyze")))
                .isInstanceOf(LlmChatClientException.class).hasStackTraceContaining("nesting depth");
        assertThat(response.body.closed).isTrue();
        fixture.server.verify();
    }

    @Test
    void validJsonPrefixCannotHideAnUnboundedTrailingBody() {
        var fixture = fixture(1024, 128);
        var response = response(answer("ready") + " ".repeat(200_000), HttpStatus.OK);
        fixture.server.expect(requestTo("https://api.deepseek.com/chat/completions")).andRespond(request -> response);

        assertThatThrownBy(() -> fixture.model.call(new Prompt("Analyze")))
                .isInstanceOf(LlmChatClientException.class).hasStackTraceContaining("configured byte limit");
        assertThat(response.body.consumed()).isLessThanOrEqualTo(1025);
        assertThat(response.body.closed).isTrue();
        fixture.server.verify();
    }

    private Fixture fixture(int bytes, int depth) {
        var properties = new DeepSeekProperties();
        properties.setApiKey("test-key");
        properties.setMaxResponseBytes(bytes);
        properties.setMaxResponseNestingDepth(depth);
        var config = new SpringAiChatConfig();
        var transport = config.deepSeekRestTemplate(new RestTemplateBuilder(), properties);
        return new Fixture(new DeepSeekSpringAiChatModel(properties, transport),
                MockRestServiceServer.createServer(transport));
    }

    private static String answer(String text) {
        return "{\"id\":\"test\",\"model\":\"test\",\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }

    private static TrackedResponse response(String body, HttpStatus status) {
        return new TrackedResponse(body.getBytes(StandardCharsets.UTF_8), status);
    }

    private record Fixture(DeepSeekSpringAiChatModel model, MockRestServiceServer server) { }

    private static final class TrackedResponse extends MockClientHttpResponse {
        private final TrackedBody body;
        private boolean closed;

        private TrackedResponse(byte[] body, HttpStatus status) {
            super(new byte[0], status);
            this.body = new TrackedBody(body);
            getHeaders().setContentType(MediaType.APPLICATION_JSON);
        }

        @Override public InputStream getBody() { return body; }
        @Override public void close() { closed = true; }
    }

    private static final class TrackedBody extends ByteArrayInputStream {
        private boolean closed;
        private TrackedBody(byte[] body) { super(body); }
        private int consumed() { return pos; }
        @Override public void close() { closed = true; }
    }
}
