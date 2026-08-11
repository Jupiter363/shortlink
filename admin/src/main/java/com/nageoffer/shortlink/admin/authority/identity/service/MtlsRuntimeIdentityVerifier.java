package com.nageoffer.shortlink.admin.authority.identity.service;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class MtlsRuntimeIdentityVerifier {

    private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";

    private final AgentIdentityConfiguration configuration;

    private final Clock clock;

    public MtlsRuntimeIdentityVerifier(
            AgentIdentityConfiguration configuration,
            @Qualifier("agentIdentityClock") Clock agentIdentityClock
    ) {
        this.configuration = configuration;
        this.clock = agentIdentityClock;
    }

    public String verify(HttpServletRequest request) {
        if (!configuration.isTokenExchangeEnabled()) {
            throw AgentIdentityException.notConfigured();
        }
        if (!request.isSecure()) {
            throw AgentIdentityException.forbidden("mTLS is required for Token Exchange.");
        }
        Object attribute = request.getAttribute(CERTIFICATE_ATTRIBUTE);
        if (!(attribute instanceof X509Certificate[] certificates) || certificates.length == 0) {
            throw AgentIdentityException.forbidden("A verified runtime client certificate is required.");
        }
        X509Certificate leaf = certificates[0];
        validateCertificate(leaf);
        String actualFingerprint = fingerprint(leaf);
        boolean matched = configuration.getRuntimeClientCertificateSha256().stream()
                .map(this::normalizeFingerprint)
                .filter(StringUtils::hasText)
                .anyMatch(expected -> MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        actualFingerprint.getBytes(StandardCharsets.US_ASCII)
                ));
        if (!matched) {
            throw AgentIdentityException.forbidden("The runtime service identity is not allowed.");
        }
        return configuration.getRuntimeServiceId();
    }

    private void validateCertificate(X509Certificate certificate) {
        try {
            certificate.checkValidity(java.util.Date.from(clock.instant()));
            if (configuration.isRequireClientAuthExtendedKeyUsage()) {
                List<String> usages = certificate.getExtendedKeyUsage();
                if (usages == null || !usages.contains(CLIENT_AUTH_EKU)) {
                    throw AgentIdentityException.forbidden("The runtime certificate is not valid for client auth.");
                }
            }
        } catch (CertificateException exception) {
            throw AgentIdentityException.forbidden("The runtime client certificate is invalid.");
        }
    }

    private String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | CertificateEncodingException exception) {
            throw AgentIdentityException.forbidden("The runtime client certificate is invalid.");
        }
    }

    private String normalizeFingerprint(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace("sha256:", "")
                .replace(":", "");
        return normalized.matches("[0-9a-f]{64}") ? normalized : "";
    }
}
