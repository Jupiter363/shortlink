package com.nageoffer.shortlink.admin.authority.identity.crypto;

import com.nageoffer.shortlink.admin.authority.identity.config.AgentIdentityConfiguration;
import com.nageoffer.shortlink.admin.authority.identity.exception.AgentIdentityException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class AgentIdentityKeyRing {

    private static final Pattern KEY_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final AgentIdentityConfiguration configuration;

    private volatile LoadedKeys loadedKeys;

    public AgentIdentityKeyRing(AgentIdentityConfiguration configuration) {
        this.configuration = configuration;
    }

    public ECKey signingKey() {
        return load().signingKey();
    }

    public ECKey verificationKey(String keyId) {
        if (!StringUtils.hasText(keyId)) {
            throw AgentIdentityException.invalidToken();
        }
        ECKey key = load().verificationKeys().get(keyId);
        if (key == null) {
            throw AgentIdentityException.invalidToken();
        }
        return key;
    }

    public JWKSet publicJwkSet() {
        return load().publicJwkSet();
    }

    private LoadedKeys load() {
        LoadedKeys current = loadedKeys;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (loadedKeys == null) {
                loadedKeys = loadConfiguredKeys();
            }
            return loadedKeys;
        }
    }

    private LoadedKeys loadConfiguredKeys() {
        if (!StringUtils.hasText(configuration.getSigningJwkFile())) {
            throw AgentIdentityException.notConfigured();
        }
        try {
            JWK parsed = JWK.parse(read(configuration.getSigningJwkFile()));
            if (!(parsed instanceof ECKey signingKey)) {
                throw AgentIdentityException.notConfigured();
            }
            validateSigningKey(signingKey);

            Map<String, ECKey> verificationKeys = new LinkedHashMap<>();
            verificationKeys.put(signingKey.getKeyID(), signingKey.toPublicJWK());
            if (StringUtils.hasText(configuration.getVerificationJwksFile())) {
                JWKSet verificationSet = JWKSet.parse(read(configuration.getVerificationJwksFile()));
                for (JWK each : verificationSet.getKeys()) {
                    if (!(each instanceof ECKey ecKey)) {
                        throw AgentIdentityException.notConfigured();
                    }
                    ECKey publicKey = ecKey.toPublicJWK();
                    validateVerificationKey(publicKey);
                    verificationKeys.put(publicKey.getKeyID(), publicKey);
                }
            }
            List<JWK> publicKeys = verificationKeys.values().stream()
                    .map(each -> (JWK) each)
                    .toList();
            return new LoadedKeys(
                    signingKey,
                    Map.copyOf(verificationKeys),
                    new JWKSet(publicKeys)
            );
        } catch (IOException | ParseException exception) {
            throw AgentIdentityException.notConfigured();
        }
    }

    private void validateSigningKey(ECKey key) {
        validateVerificationKey(key);
        if (!key.isPrivate()) {
            throw AgentIdentityException.notConfigured();
        }
    }

    private void validateVerificationKey(ECKey key) {
        if (!Curve.P_256.equals(key.getCurve())
                || !StringUtils.hasText(key.getKeyID())
                || !KEY_ID.matcher(key.getKeyID()).matches()
                || !JWSAlgorithm.ES256.equals(key.getAlgorithm())
                || !KeyUse.SIGNATURE.equals(key.getKeyUse())) {
            throw AgentIdentityException.notConfigured();
        }
    }

    private String read(String file) throws IOException {
        return Files.readString(Path.of(file).toAbsolutePath().normalize(), StandardCharsets.UTF_8);
    }

    private record LoadedKeys(
            ECKey signingKey,
            Map<String, ECKey> verificationKeys,
            JWKSet publicJwkSet
    ) {
    }
}
