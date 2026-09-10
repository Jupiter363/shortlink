package com.jupiter.shortlink.contract;

import java.nio.ByteBuffer;
import java.util.Base64;

/** Fixed p=10 HLL, only used for labelled provisional windows; canonical CH uses uniqCombined64. */
public final class HllSketch {
    private final byte[] registers;

    public HllSketch() {
        registers = new byte[1024];
    }

    public HllSketch(String encoded) {
        registers = Base64.getDecoder().decode(encoded);
        if (registers.length != 1024) throw new IllegalArgumentException("Invalid HLL");
    }

    public void add(String hash) {
        if (hash == null || hash.isEmpty()) return;
        long value =
                ByteBuffer.wrap(java.util.HexFormat.of().parseHex(hash.substring(0, 16))).getLong();
        int index = (int) (value >>> 54);
        long suffix = (value << 10) | (1L << 9);
        registers[index] = (byte) Math.max(registers[index], Long.numberOfLeadingZeros(suffix) + 1);
    }

    public void merge(HllSketch other) {
        for (int i = 0; i < registers.length; i++)
            registers[i] = (byte) Math.max(registers[i], other.registers[i]);
    }

    public double estimate() {
        double sum = 0;
        int zeros = 0;
        for (byte r : registers) {
            sum += Math.scalb(1d, -r);
            if (r == 0) zeros++;
        }
        double n = (0.7213 / (1 + 1.079 / 1024)) * 1024 * 1024 / sum;
        return n <= 2.5 * 1024 && zeros > 0 ? 1024 * Math.log(1024d / zeros) : n;
    }

    public String encode() {
        return Base64.getEncoder().encodeToString(registers);
    }
}
