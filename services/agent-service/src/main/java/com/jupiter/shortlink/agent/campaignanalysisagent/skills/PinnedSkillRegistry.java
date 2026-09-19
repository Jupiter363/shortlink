package com.jupiter.shortlink.agent.campaignanalysisagent.skills;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.SkillScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

/** Native parser/registry adapter for explicitly approved method documents, never a script loader. */
final class PinnedSkillRegistry implements SkillRegistry {
    private record Entry(RunPinnedSkills.SkillPin pin, Path directory, String description, String content) {
        SkillMetadata metadata() {
            // A new metadata object prevents native mutable metadata from changing this Run's copy.
            // Method-declared allowed_tools are instructions, never authority to activate tools.
            return SkillMetadata.builder().name(pin.name()).description(description)
                    .skillPath(directory.toString()).source("approved-run-method")
                    .fullContent(content).allowedTools(List.of()).build();
        }
    }

    private final Path approvedRoot;
    private final Map<String, Entry> entries;
    private final FileSystemSkillRegistry nativeTemplates;

    PinnedSkillRegistry(Path approvedRoot, List<RunPinnedSkills.SkillPin> pins) {
        try {
            this.approvedRoot = approvedRoot.toRealPath();
            if (!Files.isDirectory(this.approvedRoot)) throw unavailable();
            Map<String, Entry> loaded = new LinkedHashMap<>();
            for (RunPinnedSkills.SkillPin pin : pins) {
                if (loaded.containsKey(pin.name())) throw unavailable();
                Path directory = checkedDirectory(pin);
                SkillMetadata metadata = loadPinned(pin, directory);
                loaded.put(pin.name(), new Entry(pin, directory, metadata.getDescription(), metadata.getFullContent()));
            }
            entries = java.util.Collections.unmodifiableMap(loaded);
            // Use the native prompt conventions with discovery disabled and BOTH roots explicit.
            nativeTemplates = FileSystemSkillRegistry.builder().projectSkillsDirectory(this.approvedRoot.toString())
                    .userSkillsDirectory(this.approvedRoot.toString()).autoLoad(false).build();
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    void verifyPins() {
        // Verification never replaces an Entry or discovers another version/name.
        for (Entry entry : entries.values()) loadPinned(entry.pin(), entry.directory());
    }

    private Path checkedDirectory(RunPinnedSkills.SkillPin pin) throws IOException {
        Path relative = Path.of(pin.relativeDirectory());
        if (relative.isAbsolute()) throw unavailable();
        Path expected = approvedRoot.resolve(relative).normalize();
        if (!expected.startsWith(approvedRoot) || expected.equals(approvedRoot)) throw unavailable();
        Path real = expected.toRealPath();
        if (!real.equals(expected) || !Files.isDirectory(real)) throw unavailable();
        return real;
    }

    private SkillMetadata loadPinned(RunPinnedSkills.SkillPin pin, Path directory) {
        try {
            if (!checkedDirectory(pin).equals(directory)) throw unavailable();
            Path file = directory.resolve("SKILL.md");
            if (!file.toRealPath().equals(file) || !Files.isRegularFile(file)) throw unavailable();
            SkillMetadata metadata = new SkillScanner().loadSkill(directory, "approved-run-method");
            // Hash the SAME parsed strings that are copied into the immutable entry and exposed by
            // read_skill. Hashing file bytes before/after a different native read is not sufficient.
            if (metadata == null || !pin.name().equals(metadata.getName()) || metadata.getFullContent() == null
                    || !pin.sha256().equalsIgnoreCase(RunPinnedSkills.contentDigest(metadata))) throw unavailable();
            return metadata;
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    @Override public Optional<SkillMetadata> get(String name) {
        verifyPins();
        return Optional.ofNullable(entries.get(name)).map(Entry::metadata);
    }

    @Override public Optional<SkillMetadata> getByPath(String path) {
        verifyPins();
        if (path == null) return Optional.empty();
        // No filesystem operation or arbitrary path read for a model-supplied string.
        return entries.values().stream().filter(entry -> entry.directory().toString().equals(path))
                .findFirst().map(Entry::metadata);
    }

    @Override public List<SkillMetadata> listAll() {
        verifyPins();
        return entries.values().stream().map(Entry::metadata).toList();
    }

    @Override public boolean contains(String name) { return get(name).isPresent(); }
    @Override public int size() { verifyPins(); return entries.size(); }
    @Override public void reload() { throw new UnsupportedOperationException("Run method versions are immutable"); }
    @Override public String readSkillContent(String name) {
        return get(name).orElseThrow(PinnedSkillRegistry::unavailable).getFullContent();
    }
    @Override public String readSkillContentByPath(String path) {
        return getByPath(path).orElseThrow(PinnedSkillRegistry::unavailable).getFullContent();
    }
    @Override public String getSkillLoadInstructions() { return nativeTemplates.getSkillLoadInstructions(); }
    @Override public String getRegistryType() { return "pinned-filesystem"; }
    @Override public SystemPromptTemplate getSystemPromptTemplate() { return nativeTemplates.getSystemPromptTemplate(); }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Pinned skill version unavailable or changed");
    }
}
