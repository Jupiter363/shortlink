package com.nageoffer.shortlink.admin.authority.identity.service;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.crypto.AgentIdentityKeyRing;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nageoffer.shortlink.admin.authority.identity.model.AgentTokenPrincipal;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AgentTokenService {

    private static final int CONTEXT_VERSION = 1;

    private static final Pattern CONTEXT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private static final Pattern SCOPE = Pattern.compile("^[A-Za-z0-9:_-]{1,128}$");

    private static final Pattern DELEGATION_TOKEN_ID = Pattern.compile("^adt-[A-Za-z0-9_-]{1,128}$");

    private static final Pattern AUTHORITY_TOKEN_ID = Pattern.compile("^aat-[A-Za-z0-9_-]{1,128}$");

    private final AgentIdentityConfiguration configuration;

    private final AgentIdentityKeyRing keyRing;

    private final Clock clock;

    public AgentTokenService(
            AgentIdentityConfiguration configuration,
            AgentIdentityKeyRing keyRing,
            @Qualifier("agentIdentityClock") Clock clock
    ) {
        this.configuration = configuration;
        this.keyRing = keyRing;
        this.clock = clock;
    }

    public String issueDelegationToken(
            String userId,
            String username,
            String sessionId,
            Set<String> scopes
    ) {
        return issueDelegationToken(userId, username, sessionId, 1L, scopes).value();
    }

    public IssuedDelegationToken issueDelegationToken(
            String userId,
            String username,
            String sessionId,
            long grantVersion,
            Set<String> scopes
    ) {
        return issueDelegationToken(userId, username, sessionId, grantVersion, null, scopes);
    }

    public IssuedDelegationToken issueDelegationToken(
            String userId,
            String username,
            String sessionId,
            long grantVersion,
            Instant notAfter,
            Set<String> scopes
    ) {
        requireConfiguration();
        if (!StringUtils.hasText(username)
                || username.length() > 128
                || !CONTEXT_ID.matcher(sessionId == null ? "" : sessionId).matches()
                || (StringUtils.hasText(userId) && userId.length() > 256)
                || grantVersion < 1
                || scopes == null
                || scopes.isEmpty()) {
            throw AgentIdentityException.invalidExchange("Delegation context is invalid.");
        }
        Instant now = clock.instant();
        Instant configuredExpiry = now.plus(validTtl(
                configuration.getDelegationTokenTtl(),
                Duration.ofMinutes(5)
        ));
        if (notAfter != null && !notAfter.isAfter(now)) {
            throw AgentIdentityException.invalidExchange("Delegation context is invalid.");
        }
        Instant expiresAt = notAfter != null && notAfter.isBefore(configuredExpiry)
                ? notAfter
                : configuredExpiry;
        String subject = StringUtils.hasText(userId) ? userId : "username:" + username;
        String tokenId = newTokenId("adt-");
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(configuration.getIssuer())
                .audience(configuration.getRuntimeAudience())
                .subject(subject)
                .jwtID(tokenId)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim("tid", required(configuration.getDefaultTenantId()))
                .claim("sid", sessionId)
                .claim("grant_ver", grantVersion)
                .claim("scp", sortedScopes(scopes))
                .claim("ctx_ver", CONTEXT_VERSION)
                .claim("preferred_username", username)
                .build();
        return new IssuedDelegationToken(sign(claims), tokenId, expiresAt, grantVersion, Set.copyOf(scopes));
    }

    public AgentTokenPrincipal verifyDelegationToken(String token) {
        AgentTokenPrincipal principal = verify(
                token,
                configuration.getRuntimeAudience(),
                configuration.getDelegationTokenTtl()
        );
        if (principal.parentTokenId() != null
                || claim(token, "act") != null
                || !DELEGATION_TOKEN_ID.matcher(principal.tokenId()).matches()) {
            throw AgentIdentityException.invalidToken();
        }
        return principal;
    }

    public IssuedAuthorityToken issueAuthorityToken(
            AgentTokenPrincipal delegation,
            String runtimeServiceId,
            Set<String> requestedScopes
    ) {
        requireConfiguration();
        if (!configuration.getRuntimeServiceId().equals(runtimeServiceId)) {
            throw AgentIdentityException.forbidden("The runtime service identity is not allowed.");
        }
        if (!delegation.scopes().containsAll(requestedScopes) || requestedScopes.isEmpty()) {
            throw AgentIdentityException.forbidden("Requested scope exceeds the delegated scope.");
        }
        Instant now = clock.instant();
        Instant configuredExpiry = now.plus(validTtl(
                configuration.getAuthorityTokenTtl(),
                Duration.ofMinutes(2)
        ));
        Instant expiresAt = configuredExpiry.isBefore(delegation.expiresAt())
                ? configuredExpiry
                : delegation.expiresAt();
        if (!expiresAt.isAfter(now)) {
            throw AgentIdentityException.invalidToken();
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(configuration.getIssuer())
                .audience(configuration.getAuthorityAudience())
                .subject(delegation.subject())
                .jwtID(newTokenId("aat-"))
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(expiresAt))
                .claim("tid", delegation.tenantId())
                .claim("sid", delegation.sessionId())
                .claim("grant_ver", delegation.grantVersion())
                .claim("scp", sortedScopes(requestedScopes))
                .claim("ctx_ver", CONTEXT_VERSION)
                .claim("preferred_username", delegation.username())
                .claim("act", Map.of("sub", runtimeServiceId))
                .claim("parent_jti", delegation.tokenId())
                .build();
        return new IssuedAuthorityToken(sign(claims), expiresAt, Set.copyOf(requestedScopes));
    }

    public AgentTokenPrincipal verifyAuthorityToken(String token, String requiredScope) {
        AgentTokenPrincipal principal = verify(
                token,
                configuration.getAuthorityAudience(),
                configuration.getAuthorityTokenTtl()
        );
        if (!principal.hasScope(requiredScope) || !StringUtils.hasText(principal.parentTokenId())) {
            throw AgentIdentityException.invalidToken();
        }
        try {
            Map<String, Object> actor = SignedJWT.parse(token).getJWTClaimsSet().getJSONObjectClaim("act");
            if (actor == null
                    || !configuration.getRuntimeServiceId().equals(actor.get("sub"))
                    || !AUTHORITY_TOKEN_ID.matcher(principal.tokenId()).matches()
                    || !DELEGATION_TOKEN_ID.matcher(principal.parentTokenId()).matches()) {
                throw AgentIdentityException.invalidToken();
            }
        } catch (ParseException exception) {
            throw AgentIdentityException.invalidToken();
        }
        return principal;
    }

    private Object claim(String token, String name) {
        try {
            return SignedJWT.parse(token).getJWTClaimsSet().getClaim(name);
        } catch (ParseException exception) {
            throw AgentIdentityException.invalidToken();
        }
    }

    private AgentTokenPrincipal verify(String token, String expectedAudience, Duration maximumTtl) {
        requireConfiguration();
        if (!StringUtils.hasText(token) || token.length() > 16_384) {
            throw AgentIdentityException.invalidToken();
        }
        try {
            SignedJWT signedJwt = SignedJWT.parse(token);
            JWSHeader header = signedJwt.getHeader();
            if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())
                    || !JOSEObjectType.JWT.equals(header.getType())
                    || !signedJwt.verify(new ECDSAVerifier(keyRing.verificationKey(header.getKeyID())))) {
                throw AgentIdentityException.invalidToken();
            }
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            validateStandardClaims(claims, expectedAudience, maximumTtl);
            List<String> scopeValues = claims.getStringListClaim("scp");
            Set<String> scopes = normalizedScopes(scopeValues);
            Integer contextVersion = claims.getIntegerClaim("ctx_ver");
            String username = claims.getStringClaim("preferred_username");
            String tenantId = claims.getStringClaim("tid");
            String sessionId = claims.getStringClaim("sid");
            Long grantVersion = claims.getLongClaim("grant_ver");
            if (!Integer.valueOf(CONTEXT_VERSION).equals(contextVersion)
                    || !StringUtils.hasText(username)
                    || username.length() > 128
                    || !CONTEXT_ID.matcher(tenantId == null ? "" : tenantId).matches()
                    || !CONTEXT_ID.matcher(sessionId == null ? "" : sessionId).matches()
                    || grantVersion == null
                    || grantVersion < 1
                    || scopes.isEmpty()) {
                throw AgentIdentityException.invalidToken();
            }
            return new AgentTokenPrincipal(
                    required(claims.getSubject()),
                    username,
                    tenantId,
                    sessionId,
                    grantVersion,
                    scopes,
                    required(claims.getJWTID()),
                    claims.getStringClaim("parent_jti"),
                    claims.getIssueTime().toInstant(),
                    claims.getExpirationTime().toInstant()
            );
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            throw AgentIdentityException.invalidToken();
        }
    }

    private void validateStandardClaims(
            JWTClaimsSet claims,
            String expectedAudience,
            Duration maximumTtl
    ) {
        Instant now = clock.instant();
        Duration skew = validSkew(configuration.getClockSkew());
        Date issuedAt = claims.getIssueTime();
        Date notBefore = claims.getNotBeforeTime();
        Date expiresAt = claims.getExpirationTime();
        if (!configuration.getIssuer().equals(claims.getIssuer())
                || claims.getAudience().size() != 1
                || !expectedAudience.equals(claims.getAudience().get(0))
                || !StringUtils.hasText(claims.getSubject())
                || !StringUtils.hasText(claims.getJWTID())
                || issuedAt == null
                || notBefore == null
                || expiresAt == null
                || issuedAt.toInstant().isAfter(now.plus(skew))
                || notBefore.toInstant().isAfter(now.plus(skew))
                || notBefore.toInstant().isBefore(issuedAt.toInstant().minus(skew))
                || !expiresAt.toInstant().isAfter(now.minus(skew))
                || expiresAt.toInstant().isAfter(
                        issuedAt.toInstant().plus(validTtl(maximumTtl, Duration.ofMinutes(5))).plus(skew)
                )) {
            throw AgentIdentityException.invalidToken();
        }
    }

    private String sign(JWTClaimsSet claims) {
        try {
            SignedJWT signedJwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .type(JOSEObjectType.JWT)
                            .keyID(keyRing.signingKey().getKeyID())
                            .build(),
                    claims
            );
            signedJwt.sign(new ECDSASigner(keyRing.signingKey()));
            return signedJwt.serialize();
        } catch (JOSEException exception) {
            throw AgentIdentityException.notConfigured();
        }
    }

    private Set<String> normalizedScopes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String each : values) {
            if (!StringUtils.hasText(each) || !SCOPE.matcher(each).matches()) {
                throw AgentIdentityException.invalidToken();
            }
            normalized.add(each);
        }
        return Set.copyOf(normalized);
    }

    private List<String> sortedScopes(Set<String> scopes) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String each : scopes) {
            if (!StringUtils.hasText(each) || !SCOPE.matcher(each).matches()) {
                throw AgentIdentityException.invalidExchange("Requested scope is invalid.");
            }
            sorted.add(each);
        }
        return List.copyOf(sorted);
    }

    private Duration validTtl(Duration configured, Duration maximum) {
        if (configured == null || configured.isZero() || configured.isNegative()
                || configured.compareTo(maximum) > 0) {
            throw AgentIdentityException.notConfigured();
        }
        return configured;
    }

    private Duration validSkew(Duration configured) {
        if (configured == null || configured.isNegative() || configured.compareTo(Duration.ofMinutes(1)) > 0) {
            throw AgentIdentityException.notConfigured();
        }
        return configured;
    }

    private String required(String value) {
        if (!StringUtils.hasText(value)) {
            throw AgentIdentityException.invalidToken();
        }
        return value;
    }

    private String newTokenId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private void requireConfiguration() {
        if (!StringUtils.hasText(configuration.getIssuer())
                || !StringUtils.hasText(configuration.getRuntimeAudience())
                || !StringUtils.hasText(configuration.getAuthorityAudience())
                || !StringUtils.hasText(configuration.getRuntimeServiceId())
                || !StringUtils.hasText(configuration.getDefaultTenantId())
                || !CONTEXT_ID.matcher(configuration.getDefaultTenantId()).matches()) {
            throw AgentIdentityException.notConfigured();
        }
    }

    public record IssuedAuthorityToken(String value, Instant expiresAt, Set<String> scopes) {
    }

    public record IssuedDelegationToken(
            String value,
            String tokenId,
            Instant expiresAt,
            long grantVersion,
            Set<String> scopes
    ) {
    }
}
