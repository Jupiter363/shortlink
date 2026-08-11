package com.nageoffer.shortlink.admin.authority.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "short-link.agent.identity")
public class AgentIdentityConfiguration {

    public enum RuntimeAuthMode {
        LEGACY,
        DUAL,
        DELEGATION_JWT
    }

    public enum CapabilityAuthMode {
        LEGACY,
        DUAL,
        AUTHORITY_TOKEN
    }

    private RuntimeAuthMode runtimeAuthMode = RuntimeAuthMode.LEGACY;

    private CapabilityAuthMode capabilityAuthMode = CapabilityAuthMode.LEGACY;

    private String issuer = "shortlink-admin";

    private String runtimeAudience = "shortlink-agent-runtime";

    private String authorityAudience = "shortlink-authority";

    private String runtimeServiceId = "shortlink-agent-runtime";

    private String defaultTenantId = "tenant-default";

    private String signingJwkFile = "";

    private String verificationJwksFile = "";

    private Duration delegationTokenTtl = Duration.ofMinutes(5);

    private Duration authorityTokenTtl = Duration.ofMinutes(2);

    private Duration sessionGrantTtl = Duration.ofHours(8);

    private Duration clockSkew = Duration.ofSeconds(30);

    private boolean tokenExchangeEnabled = false;

    private boolean sessionGrantEnabled = false;

    private boolean requireClientAuthExtendedKeyUsage = true;

    private List<String> runtimeClientCertificateSha256 = new ArrayList<>();
}
