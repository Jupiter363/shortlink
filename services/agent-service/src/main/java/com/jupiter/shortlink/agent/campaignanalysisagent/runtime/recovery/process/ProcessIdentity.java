package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process;

import java.util.UUID;

/**
 * Backend-created identity. processDomain is trusted configuration identifying one machine and
 * one OS PID namespace; it must never come from a request or be shared by isolated containers.
 * startedAtMillis is the OS process start instant normalized to epoch milliseconds, not boot time
 * inferred from an application timer. instanceId distinguishes adapter startups within one JVM.
 */
public record ProcessIdentity(String instanceId, String processDomain, long pid, long startedAtMillis) {
    public ProcessIdentity {
        if (instanceId == null) throw new IllegalArgumentException("Process instance UUID is required");
        UUID parsed = UUID.fromString(instanceId);
        if (!parsed.toString().equalsIgnoreCase(instanceId)) {
            throw new IllegalArgumentException("Canonical process instance UUID is required");
        }
        instanceId = parsed.toString();
        requireDomain(processDomain);
        if (pid <= 0 || startedAtMillis <= 0) {
            throw new IllegalArgumentException("Positive OS PID and process start epoch milliseconds are required");
        }
    }

    static void requireDomain(String domain) {
        if (domain == null || domain.isBlank() || domain.length() > 256 || !domain.equals(domain.strip())
                || domain.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Explicit trusted process domain is required");
        }
    }
}
