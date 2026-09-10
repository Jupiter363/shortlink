package com.jupiter.shortlink.id;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Fixed 52-bit, eight-round Feistel permutation followed by nine Base62 digits. Numeric obfuscation
 * only: this custom round function is NOT a cryptographic security boundary. The key, VERSION and
 * ALPHABET must stay fixed for the lifetime of the global short-code namespace.
 */
public final class ShortCodeCodec {
    public static final String VERSION = "feistel52-mix64-8-base62-v1";
    public static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    public static final int LENGTH = 9;
    private static final int HALF_BITS = 26;
    private static final int HALF_MASK = (1 << HALF_BITS) - 1;
    private static final long GOLDEN = 0x9e3779b97f4a7c15L;
    private final long[] roundKeys = new long[8];

    public ShortCodeCodec(byte[] fixedKey) {
        Objects.requireNonNull(fixedKey);
        if (fixedKey.length != 32)
            throw new IllegalArgumentException("A fixed 32-byte key is required");
        ByteBuffer words = ByteBuffer.wrap(fixedKey.clone()).order(ByteOrder.BIG_ENDIAN);
        long[] keyWords = {words.getLong(), words.getLong(), words.getLong(), words.getLong()};
        for (int round = 0; round < roundKeys.length; round++) {
            roundKeys[round] = mix64(keyWords[round & 3] + GOLDEN * (round + 1L));
        }
    }

    public String encode(long linkId) {
        if (linkId < 1 || linkId >= IdRange.ID_LIMIT)
            throw new IllegalArgumentException("linkId outside [1, 2^52)");
        int left = (int) (linkId >>> HALF_BITS);
        int right = (int) (linkId & HALF_MASK);
        for (long roundKey : roundKeys) {
            int next = left ^ round(right, roundKey);
            left = right;
            right = next;
        }
        long value = ((long) left << HALF_BITS) | right;
        char[] digits = new char[LENGTH];
        for (int index = LENGTH - 1; index >= 0; index--) {
            digits[index] = ALPHABET.charAt((int) (value % 62));
            value /= 62;
        }
        return new String(digits);
    }

    public long decode(String shortCode) {
        if (shortCode == null || shortCode.length() != LENGTH)
            throw new IllegalArgumentException("Short code must have nine digits");
        long value = 0;
        for (int index = 0; index < LENGTH; index++) {
            int digit = ALPHABET.indexOf(shortCode.charAt(index));
            if (digit < 0 || value > (IdRange.ID_LIMIT - 1 - digit) / 62) {
                throw new IllegalArgumentException(
                        "Short code is outside the 52-bit permutation domain");
            }
            value = value * 62 + digit;
        }
        int left = (int) (value >>> HALF_BITS);
        int right = (int) (value & HALF_MASK);
        for (int index = roundKeys.length - 1; index >= 0; index--) {
            int previousLeft = right ^ round(left, roundKeys[index]);
            right = left;
            left = previousLeft;
        }
        long id = ((long) left << HALF_BITS) | right;
        if (id == 0) throw new IllegalArgumentException("Short code decodes to reserved ID zero");
        return id;
    }

    private static int round(int half, long key) {
        return (int) mix64((half & (long) HALF_MASK) ^ key) & HALF_MASK;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
