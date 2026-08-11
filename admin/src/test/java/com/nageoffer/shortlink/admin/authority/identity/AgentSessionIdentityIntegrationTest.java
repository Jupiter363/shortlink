package com.nageoffer.shortlink.admin.authority.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.crypto.AgentIdentityKeyRing;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionBootstrapRequest;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentSessionTokenResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenExchangeService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenService;
import com.nageoffer.shortlink.admin.authority.identity.service.MtlsRuntimeIdentityVerifier;
import com.nageoffer.shortlink.admin.authority.session.AgentSessionGrantStore;
import com.nageoffer.shortlink.admin.authority.session.outbox.JdbcAgentAuthorityOutboxRepository;
import com.nageoffer.shortlink.admin.authority.session.persistence.JdbcAgentSessionGrantRepository;
import com.nageoffer.shortlink.admin.authority.session.persistence.JdbcAgentTokenRevocationRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSessionIdentityIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @TempDir
    private Path tempDirectory;

    private AgentSessionLifecycleService lifecycleService;

    private AgentTokenExchangeService exchangeService;

    private JdbcTemplate jdbcTemplate;

    private MockHttpServletRequest mtlsRequest;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent_identity_" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource(
                "sql/migration/V20260717_01__agent_session_grant_authority.sql"
        )).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        ObjectMapper objectMapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentIdentityConfiguration configuration = identityConfiguration();
        AgentTokenService tokenService = new AgentTokenService(
                configuration,
                new AgentIdentityKeyRing(configuration),
                clock
        );
        AgentSessionGrantStore grantStore = new AgentSessionGrantStore(
                new JdbcAgentSessionGrantRepository(jdbcTemplate, objectMapper),
                new JdbcAgentTokenRevocationRepository(jdbcTemplate),
                new JdbcAgentAuthorityOutboxRepository(jdbcTemplate),
                objectMapper,
                clock,
                new DataSourceTransactionManager(dataSource)
        );
        lifecycleService = new AgentSessionLifecycleService(
                configuration,
                tokenService,
                grantStore,
                clock
        );
        exchangeService = new AgentTokenExchangeService(
                configuration,
                tokenService,
                new MtlsRuntimeIdentityVerifier(configuration, clock),
                lifecycleService,
                clock
        );
    }

    @Test
    void bootstrapRefreshAndRevokeConstrainTokenExchangeEndToEnd() {
        AgentSessionTokenResponse initial = lifecycleService.bootstrap(
                new AgentSessionBootstrapRequest(
                        "campaign-analysis",
                        new AgentSessionBootstrapRequest.ClientContext("zh-CN", "Asia/Shanghai")
                ),
                "1001",
                "trusted-user"
        );

        assertThat(exchange(initial.runtimeToken()).expiresIn()).isEqualTo(120);

        AgentSessionTokenResponse refreshed = lifecycleService.refresh(
                initial.sessionId(),
                "1001",
                "trusted-user",
                Map.of()
        );

        assertThat(refreshed.grantVersion()).isEqualTo(2);
        assertThatThrownBy(() -> exchange(initial.runtimeToken()))
                .isInstanceOf(AgentIdentityException.class)
                .hasMessage("The agent token is invalid.");
        assertThat(exchange(refreshed.runtimeToken()).expiresIn()).isEqualTo(120);

        lifecycleService.revoke(
                initial.sessionId(),
                "1001",
                "trusted-user",
                Map.of()
        );

        assertThatThrownBy(() -> exchange(refreshed.runtimeToken()))
                .isInstanceOf(AgentIdentityException.class)
                .hasMessage("The agent token is invalid.");
        assertThat(jdbcTemplate.queryForObject(
                "select count(1) from t_agent_authority_outbox",
                Integer.class
        )).isEqualTo(1);
    }

    private com.nageoffer.shortlink.admin.authority.identity.model.TokenExchangeResponse exchange(
            String delegationToken
    ) {
        return exchangeService.exchange(
                mtlsRequest,
                AgentTokenExchangeService.GRANT_TYPE,
                delegationToken,
                AgentTokenExchangeService.SUBJECT_TOKEN_TYPE,
                "shortlink-authority",
                "capability:stats:read"
        );
    }

    private AgentIdentityConfiguration identityConfiguration() throws Exception {
        ECKey signingKey = new ECKeyGenerator(Curve.P_256)
                .algorithm(JWSAlgorithm.ES256)
                .keyUse(KeyUse.SIGNATURE)
                .keyID("agent-session-integration")
                .generate();
        Path signingKeyFile = tempDirectory.resolve("signing.jwk");
        Files.writeString(signingKeyFile, signingKey.toJSONString(), StandardCharsets.UTF_8);
        byte[] certificateBytes = "runtime-client-certificate".getBytes(StandardCharsets.UTF_8);
        AgentIdentityConfiguration configuration = new AgentIdentityConfiguration();
        configuration.setSigningJwkFile(signingKeyFile.toString());
        configuration.setSessionGrantEnabled(true);
        configuration.setTokenExchangeEnabled(true);
        configuration.setRuntimeClientCertificateSha256(List.of(
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(certificateBytes)
                )
        ));
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(certificateBytes);
        when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        mtlsRequest = new MockHttpServletRequest();
        mtlsRequest.setSecure(true);
        mtlsRequest.setAttribute(
                "jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{certificate}
        );
        return configuration;
    }
}
