package com.jupiter.shortlink.agent.harness.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure name matching over a caller-supplied, freshly authorized group list. Does not grant access.
 */
public final class OwnedGroupNameResolver {
    private static final Set<String> DEFAULT_NAMES =
            Set.of("default", "default group", "默认", "默认分组", "默认组");
    private static final Pattern DEFAULT_REFERENCE =
            Pattern.compile("(?iu)(?<![a-z0-9_])default(?![a-z0-9_])|默认(?:分组|组)?");
    private static final Pattern GROUP_NAME =
            Pattern.compile("(?iu)\\bgroupName\\s*[:=：]\\s*([^\\s,;，；]+)");

    private OwnedGroupNameResolver() {}

    public enum Status {
        MATCHED,
        MISSING,
        AMBIGUOUS
    }

    public record Resolution(String gid, Status status) {}

    public static Resolution resolve(String message, List<?> groups) {
        String normalized = normalize(message);
        var named = GROUP_NAME.matcher(normalized);
        String explicitName = named.find() ? normalize(named.group(1)) : null;
        boolean defaultRequested =
                explicitName != null
                        ? DEFAULT_NAMES.contains(explicitName)
                        : DEFAULT_REFERENCE.matcher(normalized).find();
        Set<String> matches = new LinkedHashSet<>();
        for (Object value : groups) {
            if (!(value instanceof Map<?, ?> group)) continue;
            String gid = group.get("gid") == null ? "" : group.get("gid").toString().trim();
            String name = normalize(group.get("name"));
            if (gid.isBlank() || name.isBlank()) continue;
            boolean matchesName =
                    defaultRequested
                            ? DEFAULT_NAMES.contains(name)
                            : explicitName != null
                                    ? explicitName.equals(name)
                                    : Pattern.compile(
                                                    "(?iu)(?<![a-z0-9_])"
                                                            + Pattern.quote(name)
                                                            + "(?![a-z0-9_])")
                                            .matcher(normalized)
                                            .find();
            if (matchesName) matches.add(gid);
        }
        return matches.size() == 1
                ? new Resolution(matches.iterator().next(), Status.MATCHED)
                : new Resolution("", matches.isEmpty() ? Status.MISSING : Status.AMBIGUOUS);
    }

    public static boolean hasExplicitGroupReference(String message) {
        String normalized = normalize(message);
        return DEFAULT_REFERENCE.matcher(normalized).find()
                || GROUP_NAME.matcher(normalized).find()
                || normalized.contains("分组")
                || Pattern.compile("(?iu)\\bgroups?\\b").matcher(normalized).find();
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT);
    }
}
