package com.jupiter.shortlink.agent.campaignanalysisagent.skills;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Opt-in, unregistered P0 adapter. Trusted server code supplies the Run's persisted pins, reviewed
 * root, registered business callbacks and frozen policy; none may come from model tool arguments.
 * Restore with the SAME pins. Missing/changed versions stop, never select a newer method package.
 * This method-document adapter does not run scripts or read attachments/relative resource files.
 * The package directory must be deployment-owned and stable while native parsing occurs.
 */
@HookPositions({HookPosition.BEFORE_AGENT})
public final class RunPinnedSkills extends AgentHook {
    private static final Set<String> READ_ONLY = Set.of("read_skill", "search_skills");
    private static final Set<String> UNSAFE_GENERIC_TOOLS = Set.of("disable_skill", "disabletool", "disable_tool",
            "shell", "bash", "powershell", "cmd", "exec", "execute_shell", "run_shell", "python", "run_python");

    /** Version and contentDigest(nativeMetadata) are approved release metadata, never planner values. */
    public record SkillPin(String name, String version, String relativeDirectory, String sha256) {
        public SkillPin {
            if (name == null || !name.matches("[a-z0-9][a-z0-9-]{0,63}") || version == null || version.isBlank()
                    || relativeDirectory == null || relativeDirectory.isBlank()
                    || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Invalid approved skill pin");
            }
        }
    }

    @FunctionalInterface
    public interface CurrentAuthorization {
        /** Recheck current Run/principal capability authorization immediately before dispatch. */
        boolean permits(String runId, String toolName);
    }

    private final String runId;
    private final List<SkillPin> pins;
    private final Set<String> frozenAllowedTools;
    private final CurrentAuthorization authorization;
    private final PinnedSkillRegistry registry;
    private final SkillsAgentHook nativeHook;
    private final List<ToolCallback> methodTools;
    private final List<ToolCallback> businessTools;

    public RunPinnedSkills(String runId, Path approvedRoot, List<SkillPin> persistedPins,
                           Set<String> frozenAllowedTools, List<ToolCallback> registeredBusinessTools,
                           CurrentAuthorization authorization) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Run ID is required");
        this.runId = runId;
        this.pins = List.copyOf(persistedPins);
        this.frozenAllowedTools = Set.copyOf(frozenAllowedTools);
        this.authorization = Objects.requireNonNull(authorization);
        if (this.frozenAllowedTools.stream().anyMatch(RunPinnedSkills::unsafeName)) {
            throw new IllegalArgumentException("Only approved business tools may enter the frozen policy");
        }
        this.registry = new PinnedSkillRegistry(Objects.requireNonNull(approvedRoot), pins);
        // No groupedTools/resolver: reading a method must not activate additional callbacks.
        this.nativeHook = SkillsAgentHook.builder().skillRegistry(registry).autoReload(false).build();
        this.methodTools = nativeHook.getTools().stream()
                .filter(tool -> READ_ONLY.contains(tool.getToolDefinition().name()))
                .map(tool -> guarded(tool, false)).toList();
        Set<String> registered = new HashSet<>();
        List<ToolCallback> business = new ArrayList<>();
        for (ToolCallback tool : registeredBusinessTools) {
            String name = tool.getToolDefinition().name();
            if (name == null || name.isBlank() || !registered.add(name)) {
                throw new IllegalArgumentException("Registered tool names must be unique and nonblank");
            }
            if (this.frozenAllowedTools.contains(name)) business.add(guarded(tool, true));
        }
        if (!registered.containsAll(this.frozenAllowedTools)) {
            throw new IllegalArgumentException("Frozen policy references an unregistered business tool");
        }
        this.businessTools = List.copyOf(business);
    }

    public String runId() { return runId; }
    public List<SkillPin> pins() { return pins; }

    /**
     * Digest of the exact native-parsed method name, description and body exposed to the model.
     * Each UTF-8 value has a four-byte big-endian byte-length prefix, preventing concatenation
     * ambiguity. Paths/version are independently fixed by SkillPin; source is a fixed server label,
     * and allowedTools is always emptied. The trusted release-review process records this digest.
     */
    public static String contentDigest(SkillMetadata metadata) {
        Objects.requireNonNull(metadata);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(metadata.getName(), metadata.getDescription(), metadata.getFullContent())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    @Override public String getName() { return "RunPinnedSkills"; }

    @Override public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        registry.verifyPins();
        return nativeHook.beforeAgent(state, config);
    }

    @Override public List<ModelInterceptor> getModelInterceptors() {
        registry.verifyPins();
        return nativeHook.getModelInterceptors();
    }

    @Override public List<ToolCallback> getTools() {
        registry.verifyPins();
        List<ToolCallback> tools = new ArrayList<>(methodTools);
        businessTools.stream().filter(tool -> authorization.permits(runId, tool.getToolDefinition().name()))
                .forEach(tools::add);
        return List.copyOf(tools);
    }

    private ToolCallback guarded(ToolCallback delegate, boolean business) {
        ToolDefinition source = delegate.getToolDefinition();
        ToolDefinition definition = ToolDefinition.builder().name(source.name()).description(source.description())
                .inputSchema(source.inputSchema()).build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            private void authorize() {
                registry.verifyPins();
                if (business && (!frozenAllowedTools.contains(definition.name())
                        || !authorization.permits(runId, definition.name()))) {
                    throw new SecurityException("Tool is not authorized for this Run");
                }
            }
            @Override public String call(String input) {
                authorize();
                return delegate.call(input);
            }
            @Override public String call(String input, ToolContext context) {
                authorize();
                return delegate.call(input, context);
            }
        };
    }

    private static boolean unsafeName(String name) {
        if (name == null) return true;
        String normalized = name.toLowerCase(Locale.ROOT);
        return READ_ONLY.contains(normalized) || UNSAFE_GENERIC_TOOLS.contains(normalized);
    }
}
