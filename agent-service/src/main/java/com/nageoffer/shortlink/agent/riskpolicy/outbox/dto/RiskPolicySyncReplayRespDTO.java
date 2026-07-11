package com.nageoffer.shortlink.agent.riskpolicy.outbox.dto;

import com.nageoffer.shortlink.agent.riskpolicy.outbox.RiskPolicySyncOutboxStatus;

public record RiskPolicySyncReplayRespDTO(
        String outboxId,
        RiskPolicySyncOutboxStatus status
) {
}
