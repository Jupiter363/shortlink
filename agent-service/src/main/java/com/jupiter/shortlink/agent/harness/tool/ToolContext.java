package com.jupiter.shortlink.agent.harness.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ToolContext(
        String sessionId,
        String username,
        Map<String, Object> arguments
) {

    /**
     * Keys used by Spring AI's {@code org.springframework.ai.chat.model.ToolContext}
     * when a graph invokes one of the business tools.  They intentionally live
     * outside the model-visible argument object: the username is trusted request
     * metadata and must never be accepted as a tool argument.
     */
    public static final String SESSION_ID_KEY = "sessionId";

    public static final String USERNAME_KEY = "username";

    public ToolContext {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /**
     * Bridges Spring AI tool-call context to the small harness context used by
     * the HTTP gateway and by the backwards-compatible {@link AgentTool} API.
     *
     * <p>The conversion deliberately reads identity only from the Spring AI
     * context map.  Values with the same names in {@code arguments} are not
     * considered, preventing a model supplied {@code username} from changing
     * the identity sent to the internal admin gateway.</p>
     */
    public static ToolContext fromSpringContext(
            org.springframework.ai.chat.model.ToolContext springContext,
            Map<String, Object> arguments
    ) {
        Map<String, Object> context = springContext == null ? Map.of() : springContext.getContext();
        Map<String, Object> safeArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        safeArguments.remove(SESSION_ID_KEY);
        safeArguments.remove(USERNAME_KEY);
        return new ToolContext(
                text(context.get(SESSION_ID_KEY)),
                text(context.get(USERNAME_KEY)),
                safeArguments
        );
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        String text = Objects.toString(value, "").trim();
        return text;
    }
}
