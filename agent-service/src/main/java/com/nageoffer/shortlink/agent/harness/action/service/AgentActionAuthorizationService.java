package com.nageoffer.shortlink.agent.harness.action.service;

import com.nageoffer.shortlink.agent.harness.action.model.AgentActionActor;
import com.nageoffer.shortlink.agent.harness.action.model.AgentActionAuthorizationScope;
import com.nageoffer.shortlink.agent.harness.action.model.AgentPendingAction;
import org.springframework.stereotype.Component;

@Component
public class AgentActionAuthorizationService {

    private static final String FORBIDDEN_CODE = "ACTION_SCOPE_FORBIDDEN";
    private static final String FORBIDDEN_MESSAGE = "Agent action access is forbidden";

    public void authorize(AgentPendingAction action, AgentActionActor actor) {
        if (action == null || actor == null) {
            forbidden();
        }
        AgentActionAuthorizationScope scope = action.authorizationScope();
        if (scope == AgentActionAuthorizationScope.GID
                && hasText(actor.expectedGid())
                && actor.expectedGid().equals(action.gid())) {
            return;
        }
        if (scope == AgentActionAuthorizationScope.OWNER
                && hasText(actor.username())
                && actor.username().equals(action.ownerUsername())) {
            return;
        }
        forbidden();
    }

    public void authorizePage(String gid, AgentActionActor actor) {
        if (!hasText(gid)
                || actor == null
                || !hasText(actor.expectedGid())
                || !gid.equals(actor.expectedGid())) {
            forbidden();
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void forbidden() {
        throw new AgentActionException(FORBIDDEN_CODE, FORBIDDEN_MESSAGE);
    }
}
