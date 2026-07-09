package com.nageoffer.shortlink.agent.securityriskagent.rule;

import com.nageoffer.shortlink.agent.securityriskagent.model.RiskSignal;

import java.util.List;
import java.util.Map;

public interface RiskRule {

    List<RiskSignal> evaluate(Map<String, Object> execution);
}
