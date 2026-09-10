package com.jupiter.shortlink.redirect.web;

import java.security.DrbgParameters;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv4 values from a separately seeded SUN DRBG for each calling thread.
 *
 * <p>The JDK obtains seed entropy; no time, thread ID or application seed is used. First use and
 * reseeding may still wait for the system entropy source. The per-thread state avoids sharing the
 * default NativePRNG's per-request RandomIO path; this is not a promise of a lock-free provider.
 * Instances live with their calling threads, with no global map of thread identities.
 */
final class SecureRequestIds {
    private static final DrbgParameters.NextBytes GENERATION =
            DrbgParameters.nextBytes(128, false, null);
    private static final ThreadLocal<SecureRandom> RANDOM =
            ThreadLocal.withInitial(SecureRequestIds::newRandom);

    private SecureRequestIds() {}

    /** Validate the provider and its entropy source on the startup thread, without a fallback. */
    static void verifyAvailable() {
        randomUuid();
    }

    static UUID randomUuid() {
        byte[] bytes = new byte[16];
        RANDOM.get().nextBytes(bytes, GENERATION);

        // The same version and variant bits as UUID.randomUUID(): 122 random bits remain.
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0;
        long least = 0;
        for (int i = 0; i < 8; i++) most = (most << 8) | (bytes[i] & 0xffL);
        for (int i = 8; i < 16; i++) least = (least << 8) | (bytes[i] & 0xffL);
        return new UUID(most, least);
    }

    private static SecureRandom newRandom() {
        try {
            SecureRandom random =
                    SecureRandom.getInstance(
                            "DRBG",
                            DrbgParameters.instantiation(
                                    128, DrbgParameters.Capability.RESEED_ONLY, null),
                            "SUN");
            if (!(random.getParameters() instanceof DrbgParameters.Instantiation parameters)
                    || parameters.getStrength() < 128
                    || !parameters.getCapability().supportsReseeding()) {
                throw new IllegalStateException("Required request ID DRBG parameters unavailable");
            }
            return random;
        } catch (GeneralSecurityException unavailable) {
            // Never fall back to a predictable generator or to the shared NativePRNG path.
            throw new IllegalStateException("Required request ID DRBG unavailable", unavailable);
        }
    }
}
