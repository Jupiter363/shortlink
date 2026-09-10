package com.jupiter.shortlink.agent.harness.tool;

import com.jupiter.shortlink.agent.tool.shortlink.ListGroupsTool;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class SpringToolContextAuthorizationTest {

    @Test
    void modelSuppliedIdentityArgumentsCannotOverrideTrustedToolContext() {
        org.springframework.ai.chat.model.ToolContext springContext =
                new org.springframework.ai.chat.model.ToolContext(Map.of(
                        ToolContext.SESSION_ID_KEY, "session-001",
                        ToolContext.USERNAME_KEY, "alice"
                ));

        ToolContext context = ToolContext.fromSpringContext(
                springContext,
                Map.of(
                        "gid", "group-owned-by-bob",
                        "username", "mallory",
                        "sessionId", "mallory-session"
                )
        );

        assertThat(context.sessionId()).isEqualTo("session-001");
        assertThat(context.username()).isEqualTo("alice");
        assertThat(context.arguments())
                .containsEntry("gid", "group-owned-by-bob")
                .doesNotContainKeys("username", "sessionId");
    }

    @Test
    void missingTrustedUsernameFailsClosed() {
        AtomicBoolean gatewayCalled = new AtomicBoolean();
        ListGroupsTool tool = new ListGroupsTool((path, context, queryParams) -> {
            gatewayCalled.set(true);
            return ToolResult.success(Map.of());
        });

        ToolResult result = tool.listGroups(new org.springframework.ai.chat.model.ToolContext(Map.of()));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Missing trusted tool context username");
        assertThat(gatewayCalled).isFalse();
    }
}
