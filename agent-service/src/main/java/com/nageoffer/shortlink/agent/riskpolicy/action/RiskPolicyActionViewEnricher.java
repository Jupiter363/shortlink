package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionViewEnricher;
import com.nageoffer.shortlink.agent.riskpolicy.model.EffectiveRiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.model.RiskPolicy;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcEffectiveRiskPolicyRepository;
import com.nageoffer.shortlink.agent.riskpolicy.repository.JdbcRiskPolicyRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class RiskPolicyActionViewEnricher implements AgentActionViewEnricher {

    private final JdbcEffectiveRiskPolicyRepository effectiveRepository;
    private final JdbcRiskPolicyRepository policyRepository;

    public RiskPolicyActionViewEnricher(
            JdbcEffectiveRiskPolicyRepository effectiveRepository,
            JdbcRiskPolicyRepository policyRepository
    ) {
        this.effectiveRepository = Objects.requireNonNull(effectiveRepository, "effectiveRepository must not be null");
        this.policyRepository = Objects.requireNonNull(policyRepository, "policyRepository must not be null");
    }

    @Override
    public Map<String, Object> enrich(
            AgentPendingAction action,
            Map<String, Object> safeResult
    ) {
        Map<String, Object> result = safeResult == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(safeResult);
        String policyKey = text(result.get("policyKey"));
        String policyId = text(result.get("policyId"));
        Long policyVersion = positiveLong(result.get("policyVersion"));
        if (!StringUtils.hasText(policyKey)
                || !StringUtils.hasText(policyId)
                || policyVersion == null) {
            return result;
        }

        RiskPolicy history = policyRepository.findByPolicyId(policyId).orElse(null);
        if (history != null) {
            result.put("policyStatus", history.status().name());
        }
        EffectiveRiskPolicy effective = effectiveRepository.findByPolicyKey(policyKey).orElse(null);
        boolean matching = effective != null
                && effective.policyId().equals(policyId)
                && effective.policyVersion() == policyVersion;
        result.put("effective", matching);
        if (matching) {
            result.put("syncStatus", effective.syncStatus().name());
            result.put("desiredState", effective.desiredState().name());
        }
        return result;
    }

    private String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private Long positiveLong(Object value) {
        if (value instanceof Number number) {
            long converted = number.longValue();
            return converted > 0 ? converted : null;
        }
        if (value instanceof String text) {
            try {
                long converted = Long.parseLong(text);
                return converted > 0 ? converted : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
