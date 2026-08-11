package com.nageoffer.shortlink.admin.authority.identity.context;

import com.nageoffer.shortlink.admin.authority.identity.model.AgentTokenPrincipal;

import java.util.Set;

public final class AgentAuthorityContext {

    private static final ThreadLocal<Snapshot> CURRENT = new ThreadLocal<>();

    private AgentAuthorityContext() {
    }

    public static void set(AgentTokenPrincipal principal, String actorService) {
        CURRENT.set(new Snapshot(
                principal.subject(),
                principal.username(),
                principal.tenantId(),
                principal.sessionId(),
                principal.grantVersion(),
                principal.scopes(),
                principal.tokenId(),
                principal.parentTokenId(),
                actorService
        ));
    }

    public static Snapshot get() {
        return CURRENT.get();
    }

    public static void remove() {
        CURRENT.remove();
    }

    public record Snapshot(
            String subject,
            String username,
            String tenantId,
            String sessionId,
            long grantVersion,
            Set<String> scopes,
            String tokenId,
            String parentTokenId,
            String actorService
    ) {
    }
}
