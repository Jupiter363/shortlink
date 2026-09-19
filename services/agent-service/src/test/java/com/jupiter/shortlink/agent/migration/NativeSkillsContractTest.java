package com.jupiter.shortlink.agent.migration;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises native Skill discovery/read hooks; package version pinning remains an application duty. */
class NativeSkillsContractTest {
    @TempDir Path root;

    @Test
    void readsOnlyExplicitlyRegisteredSkillRootsAndRejectsAnUnregisteredPath() throws Exception {
        Path approved = Files.createDirectories(root.resolve("approved"));
        Path emptyUserRoot = Files.createDirectories(root.resolve("empty-user"));
        writeSkill(approved, "decline-selection", "Compare frozen periods and preserve unknown values.");
        Path outside = writeSkill(root.resolve("outside"), "unregistered", "OUTSIDE_CONTENT_MUST_NOT_LEAK");
        var registry = FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(approved.toString()).userSkillsDirectory(emptyUserRoot.toString())
                .autoLoad(true).build();
        var hook = SkillsAgentHook.builder().skillRegistry(registry).autoReload(false).build();
        var read = hook.getTools().stream()
                .filter(tool -> tool.getToolDefinition().name().equals("read_skill")).findFirst().orElseThrow();

        assertThat(registry.listAll()).extracting(skill -> skill.getName()).containsExactly("decline-selection");
        assertThat(read.call("{\"skill_name\":\"decline-selection\"}"))
                .contains("Compare frozen periods");
        String escapedPath = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(Map.of("skill_path", outside.toString()));
        assertThat(read.call(escapedPath)).doesNotContain("OUTSIDE_CONTENT_MUST_NOT_LEAK");
        assertThat(registry.contains("unregistered")).isFalse();
    }

    @Test
    void disabledAutoReloadDoesNotDiscoverNewMethodsBetweenAdvances() throws Exception {
        Path approved = Files.createDirectories(root.resolve("approved"));
        Path emptyUserRoot = Files.createDirectories(root.resolve("empty-user"));
        writeSkill(approved, "first-method", "Use only registered data tools.");
        var registry = FileSystemSkillRegistry.builder()
                .projectSkillsDirectory(approved.toString()).userSkillsDirectory(emptyUserRoot.toString())
                .autoLoad(true).build();
        var hook = SkillsAgentHook.builder().skillRegistry(registry).autoReload(false).build();
        hook.beforeAgent(new OverAllState(), RunnableConfig.builder().build()).join();
        writeSkill(approved, "later-method", "Not approved for the already frozen run.");

        hook.beforeAgent(new OverAllState(), RunnableConfig.builder().build()).join();

        assertThat(registry.contains("later-method")).isFalse();
        assertThat(registry.size()).isEqualTo(1);
        // Explicit reload is a separate configuration action, not a model-granted capability.
        registry.reload();
        assertThat(registry.contains("later-method")).isTrue();
    }

    private Path writeSkill(Path directory, String name, String instructions) throws Exception {
        Path skill = Files.createDirectories(directory.resolve(name)).resolve("SKILL.md");
        Files.writeString(skill, "---\nname: " + name + "\ndescription: Controlled test method\n---\n\n" + instructions);
        return skill;
    }
}
