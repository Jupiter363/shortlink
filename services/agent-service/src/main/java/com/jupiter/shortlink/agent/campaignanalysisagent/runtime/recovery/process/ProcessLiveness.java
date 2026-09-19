package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.recovery.process;

import java.util.Objects;
import java.util.Set;

/** Read-only backend proof port. An observation is not a lock or permission to send a request. */
@FunctionalInterface
public interface ProcessLiveness {
    enum State { ALIVE, DEAD, UNKNOWN }

    String PROCESS_ALIVE = "PROCESS_ALIVE";
    String PROCESS_ABSENT = "PROCESS_ABSENT";
    String PROCESS_EXITED = "PROCESS_EXITED";
    String PROCESS_ID_REUSED = "PROCESS_ID_REUSED";
    String PROCESS_DOMAIN_MISMATCH = "PROCESS_DOMAIN_MISMATCH";
    String PROCESS_START_UNAVAILABLE = "PROCESS_START_UNAVAILABLE";
    String PROCESS_ACCESS_DENIED = "PROCESS_ACCESS_DENIED";
    String PROCESS_PROBE_UNSUPPORTED = "PROCESS_PROBE_UNSUPPORTED";

    record Observation(State state, String reasonCode) {
        public Observation {
            Objects.requireNonNull(state);
            Objects.requireNonNull(reasonCode);
            Set<String> allowed = switch (state) {
                case ALIVE -> Set.of(PROCESS_ALIVE);
                case DEAD -> Set.of(PROCESS_ABSENT, PROCESS_EXITED, PROCESS_ID_REUSED);
                case UNKNOWN -> Set.of(PROCESS_DOMAIN_MISMATCH, PROCESS_START_UNAVAILABLE,
                        PROCESS_ACCESS_DENIED, PROCESS_PROBE_UNSUPPORTED);
            };
            if (!allowed.contains(reasonCode)) throw new IllegalArgumentException("Invalid process observation code");
        }
    }

    Observation observe(ProcessIdentity identity);
}
