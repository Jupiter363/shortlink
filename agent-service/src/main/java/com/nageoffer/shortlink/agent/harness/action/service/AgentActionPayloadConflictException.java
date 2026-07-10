package com.nageoffer.shortlink.agent.harness.action.service;

public class AgentActionPayloadConflictException extends AgentActionException {

    public AgentActionPayloadConflictException() {
        super("ACTION_PAYLOAD_CONFLICT", "Agent action payload conflicts with an existing action");
    }
}
