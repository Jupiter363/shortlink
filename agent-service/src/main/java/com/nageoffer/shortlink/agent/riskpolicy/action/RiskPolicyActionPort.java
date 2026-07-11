package com.nageoffer.shortlink.agent.riskpolicy.action;

@FunctionalInterface
public interface RiskPolicyActionPort {

    RiskPolicyConfirmedActionResult execute(RiskPolicyConfirmedActionCommand command);
}
