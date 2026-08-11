package com.nageoffer.shortlink.admin.authority.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.shortlink.admin.authority.identity.api.AgentJwksController;
import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.context.AgentAuthorityContext;
import com.nageoffer.shortlink.admin.authority.identity.crypto.AgentIdentityKeyRing;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentTokenPrincipal;
import com.nageoffer.shortlink.admin.authority.identity.model.TokenExchangeResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenExchangeService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentSessionLifecycleService;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenService;
import com.nageoffer.shortlink.admin.authority.identity.service.MtlsRuntimeIdentityVerifier;
import com.nageoffer.shortlink.admin.common.biz.agent.AgentInternalToolApiFilter;
import com.nageoffer.shortlink.admin.common.biz.user.UserContext;
import com.nageoffer.shortlink.admin.config.AgentAdminConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentIdentitySecurityTest {

    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");

    @TempDir
    private Path tempDirectory;

    private AgentIdentityConfiguration configuration;

    private AgentIdentityKeyRing keyRing;

    private AgentTokenService tokenService;

    private Clock clock;

    @BeforeEach
    void setUp() throws Exception {
        ECKey signingKey = new ECKeyGenerator(Curve.P_256)
                .algorithm(JWSAlgorithm.ES256)
                .keyUse(KeyUse.SIGNATURE)
                .keyID("agent-signing-2026-07")
                .generate();
        Path signingKeyFile = tempDirectory.resolve("signing.jwk");
        Files.writeString(signingKeyFile, signingKey.toJSONString(), StandardCharsets.UTF_8);
        configuration = new AgentIdentityConfiguration();
        configuration.setSigningJwkFile(signingKeyFile.toString());
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        keyRing = new AgentIdentityKeyRing(configuration);
        tokenService = new AgentTokenService(configuration, keyRing, clock);
    }

    @AfterEach
    void tearDown() {
        UserContext.removeUser();
        AgentAuthorityContext.remove();
    }

    @Test
    void jwksPublishesOnlyPublicEs256Material() {
        Map<String, Object> body = new AgentJwksController(keyRing).jwks().getBody();

        assertThat(body).isNotNull();
        assertThat(body.toString()).contains("agent-signing-2026-07", "P-256", "ES256");
        assertThat(keyRing.publicJwkSet().getKeys()).allMatch(each -> !each.isPrivate());
    }

    @Test
    void keyRingPublishesActiveAndRotationWindowKeys() throws Exception {
        ECKey previous = new ECKeyGenerator(Curve.P_256)
                .algorithm(JWSAlgorithm.ES256)
                .keyUse(KeyUse.SIGNATURE)
                .keyID("agent-signing-previous")
                .generate();
        Path verificationFile = tempDirectory.resolve("verification.jwks");
        Files.writeString(
                verificationFile,
                new JWKSet(previous.toPublicJWK()).toString(false),
                StandardCharsets.UTF_8
        );
        configuration.setVerificationJwksFile(verificationFile.toString());

        AgentIdentityKeyRing rotatingKeyRing = new AgentIdentityKeyRing(configuration);

        assertThat(rotatingKeyRing.publicJwkSet().getKeys())
                .extracting(each -> each.getKeyID())
                .containsExactlyInAnyOrder("agent-signing-2026-07", "agent-signing-previous");
    }

    @Test
    void tokenExchangeResponseMatchesSharedCrossLanguageFixture() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode expected = objectMapper.readTree(Files.readString(
                repositoryRoot().resolve(
                        "schemas/agent-identity/v1/examples/token-exchange-response.json"
                ),
                StandardCharsets.UTF_8
        ));
        JsonNode actual = objectMapper.valueToTree(new TokenExchangeResponse(
                "eyJ.test-authority-token.signature",
                AgentTokenExchangeService.ISSUED_TOKEN_TYPE,
                "Bearer",
                120,
                "capability:stats:read"
        ));

        assertThat(objectMapper.writeValueAsString(actual))
                .isEqualTo(objectMapper.writeValueAsString(expected));
    }

    @Test
    void delegationAndAuthorityTokensHaveSeparatedAudiencesAndBoundedScopes() {
        String delegationToken = delegationToken();
        AgentTokenPrincipal delegation = tokenService.verifyDelegationToken(delegationToken);

        assertThat(delegation.username()).isEqualTo("trusted-user");
        assertThat(delegation.tenantId()).isEqualTo("tenant-default");
        assertThat(delegation.sessionId()).isEqualTo("session-1");
        assertThat(delegation.grantVersion()).isEqualTo(1L);
        assertThatThrownBy(() -> tokenService.verifyAuthorityToken(
                delegationToken,
                "capability:stats:read"
        )).isInstanceOf(AgentIdentityException.class);

        AgentTokenService.IssuedAuthorityToken authority = tokenService.issueAuthorityToken(
                delegation,
                "shortlink-agent-runtime",
                Set.of("capability:stats:read")
        );
        AgentTokenPrincipal verified = tokenService.verifyAuthorityToken(
                authority.value(),
                "capability:stats:read"
        );

        assertThat(verified.parentTokenId()).isEqualTo(delegation.tokenId());
        assertThat(verified.grantVersion()).isEqualTo(delegation.grantVersion());
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(120));
        assertThatThrownBy(() -> tokenService.issueAuthorityToken(
                delegation,
                "shortlink-agent-runtime",
                Set.of("action:proposal:create")
        )).isInstanceOf(AgentIdentityException.class)
                .hasMessage("Requested scope exceeds the delegated scope.");
    }

    @Test
    void delegationTokenExposesPersistableIdentityAndGrantVersion() {
        AgentTokenService.IssuedDelegationToken issued = tokenService.issueDelegationToken(
                "1001",
                "trusted-user",
                "session-1",
                7L,
                Set.of("agent:run")
        );

        AgentTokenPrincipal verified = tokenService.verifyDelegationToken(issued.value());

        assertThat(issued.tokenId()).isEqualTo(verified.tokenId());
        assertThat(issued.expiresAt()).isEqualTo(verified.expiresAt());
        assertThat(issued.grantVersion()).isEqualTo(7L);
        assertThat(verified.grantVersion()).isEqualTo(7L);
    }

    @Test
    void tokenExchangeRequiresAllowedMtlsCertificateAndIssuesRfc8693Response() throws Exception {
        byte[] certificateBytes = "runtime-client-certificate".getBytes(StandardCharsets.UTF_8);
        configuration.setTokenExchangeEnabled(true);
        configuration.setSessionGrantEnabled(true);
        configuration.setRuntimeClientCertificateSha256(List.of(
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(certificateBytes)
                )
        ));
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(certificateBytes);
        when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute(
                "jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{certificate}
        );
        AgentSessionLifecycleService lifecycleService = mock(AgentSessionLifecycleService.class);
        AgentTokenExchangeService exchangeService = new AgentTokenExchangeService(
                configuration,
                tokenService,
                new MtlsRuntimeIdentityVerifier(configuration, clock),
                lifecycleService,
                clock
        );

        TokenExchangeResponse response = exchangeService.exchange(
                request,
                AgentTokenExchangeService.GRANT_TYPE,
                delegationToken(),
                AgentTokenExchangeService.SUBJECT_TOKEN_TYPE,
                "shortlink-authority",
                "capability:stats:read"
        );

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.issuedTokenType()).isEqualTo(
                AgentTokenExchangeService.ISSUED_TOKEN_TYPE
        );
        assertThat(response.expiresIn()).isEqualTo(120);
        assertThat(tokenService.verifyAuthorityToken(
                response.accessToken(),
                "capability:stats:read"
        ).username()).isEqualTo("trusted-user");
        verify(lifecycleService).requireActive(
                org.mockito.ArgumentMatchers.any(AgentTokenPrincipal.class),
                org.mockito.ArgumentMatchers.startsWith("adt-")
        );
    }

    @Test
    void tokenExchangeRejectsMissingClientCertificate() {
        configuration.setTokenExchangeEnabled(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        AgentTokenExchangeService exchangeService = new AgentTokenExchangeService(
                configuration,
                tokenService,
                new MtlsRuntimeIdentityVerifier(configuration, clock),
                clock
        );

        assertThatThrownBy(() -> exchangeService.exchange(
                request,
                AgentTokenExchangeService.GRANT_TYPE,
                delegationToken(),
                AgentTokenExchangeService.SUBJECT_TOKEN_TYPE,
                "shortlink-authority",
                "capability:stats:read"
        )).isInstanceOf(AgentIdentityException.class)
                .hasMessage("A verified runtime client certificate is required.");
    }

    @Test
    void capabilityFilterUsesAuthorityClaimsAndClearsUserContext() throws Exception {
        configuration.setCapabilityAuthMode(
                AgentIdentityConfiguration.CapabilityAuthMode.AUTHORITY_TOKEN
        );
        configuration.setSessionGrantEnabled(true);
        AgentTokenPrincipal delegation = tokenService.verifyDelegationToken(delegationToken());
        String authorityToken = tokenService.issueAuthorityToken(
                delegation,
                "shortlink-agent-runtime",
                Set.of("capability:stats:read")
        ).value();
        AgentSessionLifecycleService lifecycleService = mock(AgentSessionLifecycleService.class);
        AgentInternalToolApiFilter filter = new AgentInternalToolApiFilter(
                new AgentAdminConfiguration(),
                configuration,
                tokenService,
                lifecycleService
        );
        MockHttpServletRequest request = capabilityRequest();
        request.addHeader("Authorization", "Bearer " + authorityToken);
        request.addHeader("X-Agent-Username", "spoofed-user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            invoked.set(true);
            assertThat(UserContext.getUsername()).isEqualTo("trusted-user");
            assertThat(UserContext.getUserId()).isEqualTo("1001");
            assertThat(AgentAuthorityContext.get().tenantId()).isEqualTo("tenant-default");
            assertThat(AgentAuthorityContext.get().sessionId()).isEqualTo("session-1");
            assertThat(AgentAuthorityContext.get().grantVersion()).isEqualTo(1L);
            assertThat(AgentAuthorityContext.get().scopes())
                    .containsExactly("capability:stats:read");
        });

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContext.getUsername()).isNull();
        assertThat(AgentAuthorityContext.get()).isNull();
        verify(lifecycleService).requireActive(
                org.mockito.ArgumentMatchers.any(AgentTokenPrincipal.class),
                org.mockito.ArgumentMatchers.eq(delegation.tokenId())
        );
    }

    @Test
    void groupsCapabilityRequiresGroupReadScopeAndIgnoresSpoofedIdentityHeader() throws Exception {
        configuration.setCapabilityAuthMode(
                AgentIdentityConfiguration.CapabilityAuthMode.AUTHORITY_TOKEN
        );
        AgentTokenPrincipal delegation = tokenService.verifyDelegationToken(delegationToken(Set.of(
                "agent:run",
                "capability:group:read",
                "capability:stats:read"
        )));
        String groupAuthorityToken = tokenService.issueAuthorityToken(
                delegation,
                "shortlink-agent-runtime",
                Set.of("capability:group:read")
        ).value();
        AgentInternalToolApiFilter filter = new AgentInternalToolApiFilter(
                new AgentAdminConfiguration(),
                configuration,
                tokenService
        );
        MockHttpServletRequest request = groupsCapabilityRequest();
        request.addHeader("Authorization", "Bearer " + groupAuthorityToken);
        request.addHeader("X-Agent-Username", "spoofed-user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            invoked.set(true);
            assertThat(UserContext.getUsername()).isEqualTo("trusted-user");
            assertThat(AgentAuthorityContext.get().scopes())
                    .containsExactly("capability:group:read");
        });

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContext.getUsername()).isNull();
        assertThat(AgentAuthorityContext.get()).isNull();
    }

    @Test
    void groupsCapabilityRejectsAuthorityTokenWithoutGroupReadScope() throws Exception {
        configuration.setCapabilityAuthMode(
                AgentIdentityConfiguration.CapabilityAuthMode.AUTHORITY_TOKEN
        );
        AgentTokenPrincipal delegation = tokenService.verifyDelegationToken(delegationToken());
        String statsAuthorityToken = tokenService.issueAuthorityToken(
                delegation,
                "shortlink-agent-runtime",
                Set.of("capability:stats:read")
        ).value();
        AgentInternalToolApiFilter filter = new AgentInternalToolApiFilter(
                new AgentAdminConfiguration(),
                configuration,
                tokenService
        );
        MockHttpServletRequest request = groupsCapabilityRequest();
        request.addHeader("Authorization", "Bearer " + statsAuthorityToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void shortLinksCapabilityRequiresGroupReadScope() throws Exception {
        configuration.setCapabilityAuthMode(
                AgentIdentityConfiguration.CapabilityAuthMode.AUTHORITY_TOKEN
        );
        AgentTokenPrincipal delegation = tokenService.verifyDelegationToken(delegationToken(Set.of(
                "agent:run",
                "capability:group:read"
        )));
        String groupAuthorityToken = tokenService.issueAuthorityToken(
                delegation,
                "shortlink-agent-runtime",
                Set.of("capability:group:read")
        ).value();
        AgentInternalToolApiFilter filter = new AgentInternalToolApiFilter(
                new AgentAdminConfiguration(),
                configuration,
                tokenService
        );
        MockHttpServletRequest request = shortLinksCapabilityRequest();
        request.addHeader("Authorization", "Bearer " + groupAuthorityToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            invoked.set(true);
            assertThat(UserContext.getUsername()).isEqualTo("trusted-user");
            assertThat(AgentAuthorityContext.get().scopes())
                    .containsExactly("capability:group:read");
        });

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(UserContext.getUsername()).isNull();
        assertThat(AgentAuthorityContext.get()).isNull();
    }

    @Test
    void shortLinksCapabilityRejectsAuthorityTokenWithoutGroupReadScope() throws Exception {
        configuration.setCapabilityAuthMode(
                AgentIdentityConfiguration.CapabilityAuthMode.AUTHORITY_TOKEN
        );
        AgentTokenPrincipal delegation = tokenService.verifyDelegationToken(delegationToken());
        String statsAuthorityToken = tokenService.issueAuthorityToken(
                delegation,
                "shortlink-agent-runtime",
                Set.of("capability:stats:read")
        ).value();
        AgentInternalToolApiFilter filter = new AgentInternalToolApiFilter(
                new AgentAdminConfiguration(),
                configuration,
                tokenService
        );
        MockHttpServletRequest request = shortLinksCapabilityRequest();
        request.addHeader("Authorization", "Bearer " + statsAuthorityToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(UserContext.getUsername()).isNull();
    }

    @Test
    void dualCapabilityAuthNeverFallsBackAfterMalformedBearer() throws Exception {
        configuration.setCapabilityAuthMode(AgentIdentityConfiguration.CapabilityAuthMode.DUAL);
        AgentAdminConfiguration legacy = new AgentAdminConfiguration();
        legacy.setInternalToken("legacy-token");
        AgentInternalToolApiFilter filter = new AgentInternalToolApiFilter(
                legacy,
                configuration,
                tokenService
        );
        MockHttpServletRequest request = capabilityRequest();
        request.addHeader("Authorization", "Bearer invalid");
        request.addHeader("X-Agent-Internal-Token", "legacy-token");
        request.addHeader("X-Agent-Username", "trusted-user");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(UserContext.getUsername()).isNull();
    }

    private String delegationToken() {
        return delegationToken(Set.of("agent:run", "capability:stats:read"));
    }

    private String delegationToken(Set<String> scopes) {
        return tokenService.issueDelegationToken(
                "1001",
                "trusted-user",
                "session-1",
                scopes
        );
    }

    private MockHttpServletRequest capabilityRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query"
        );
        request.setServletPath(
                "/internal/short-link-admin/v1/agent-capabilities/v1/group-stats/query"
        );
        return request;
    }

    private MockHttpServletRequest groupsCapabilityRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/internal/short-link-admin/v1/agent-capabilities/v1/groups/list"
        );
        request.setServletPath(
                "/internal/short-link-admin/v1/agent-capabilities/v1/groups/list"
        );
        return request;
    }

    private MockHttpServletRequest shortLinksCapabilityRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query"
        );
        request.setServletPath(
                "/internal/short-link-admin/v1/agent-capabilities/v1/short-links/query"
        );
        return request;
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("schemas"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("schemas"))) {
            return parent;
        }
        throw new IllegalStateException("Repository root was not found");
    }
}
