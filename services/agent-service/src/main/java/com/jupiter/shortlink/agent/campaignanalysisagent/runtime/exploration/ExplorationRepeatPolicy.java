package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.exploration;

import com.jupiter.shortlink.agent.campaignanalysisagent.planning.PlanSpec.ExecutorRef;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import java.util.Comparator;
import java.util.Set;

/** Server opt-in for exact requests to explicitly declared reusable, read-only capabilities. */
public record ExplorationRepeatPolicy(String version, Set<ExecutorRef> reusableExecutors) {
    public ExplorationRepeatPolicy {
        if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}") || reusableExecutors == null)
            throw new IllegalArgumentException("EXPLORATION_REPEAT_POLICY_INVALID");
        reusableExecutors = Set.copyOf(reusableExecutors);
        for (ExecutorRef executor : reusableExecutors) {
            if (executor.kind() == null || executor.name() == null || executor.name().isBlank()
                    || executor.version() == null || executor.version().isBlank())
                throw new IllegalArgumentException("EXPLORATION_REPEAT_EXECUTOR_INVALID");
        }
    }

    public static ExplorationRepeatPolicy disabled() { return new ExplorationRepeatPolicy("disabled", Set.of()); }
    public boolean enabled() { return !reusableExecutors.isEmpty(); }

    public String configurationId() {
        StringBuilder material = new StringBuilder("exact-request-repeat/v1");
        append(material, version);
        reusableExecutors.stream().sorted(Comparator.comparing((ExecutorRef ref) -> ref.kind().name())
                .thenComparing(ExecutorRef::name).thenComparing(ExecutorRef::version)).forEach(ref -> {
                    append(material, ref.kind().name()); append(material, ref.name()); append(material, ref.version());
                });
        return CampaignRunStore.sha256(material.toString());
    }

    private static void append(StringBuilder target, String value) { target.append(':').append(value.length()).append(':').append(value); }
}
