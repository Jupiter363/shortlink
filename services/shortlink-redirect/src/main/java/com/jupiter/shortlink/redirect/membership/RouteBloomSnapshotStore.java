package com.jupiter.shortlink.redirect.membership;

import com.google.common.hash.BloomFilter;
import com.jupiter.shortlink.membership.AppliedCut;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Optional local acceleration. A disk snapshot never stores or restores a denial lease. */
final class RouteBloomSnapshotStore {
    private static final int MAGIC = 0x534C4246;
    private final RouteMembershipProperties config;

    record Snapshot(String namespace, AppliedCut cut, BloomFilter<RouteBloomKey> filter) {}

    RouteBloomSnapshotStore(RouteMembershipProperties config) {
        this.config = config;
    }

    Snapshot read() throws IOException {
        if (config.snapshotPath().isBlank()) return null;
        Path path = Path.of(config.snapshotPath());
        if (!Files.exists(path)) return null;
        long size = Files.size(path);
        if (size < 48 || size > config.filterBytes() + 4096)
            throw new IOException("Membership snapshot size outside budget");
        byte[] bytes;
        try (var source = Files.newInputStream(path)) {
            bytes = source.readNBytes(Math.toIntExact(config.filterBytes() + 4097));
        }
        if (bytes.length < 48 || bytes.length > config.filterBytes() + 4096)
            throw new IOException("Membership snapshot changed beyond its size budget");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(
                digest(payload), Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length)))
            throw new IOException("Membership snapshot checksum mismatch");
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC
                    || input.readInt() != 1
                    || input.readLong() != config.expectedInsertions()
                    || Double.compare(input.readDouble(), config.falsePositiveProbability()) != 0)
                throw new IOException("Membership snapshot format or capacity mismatch");
            String namespace = input.readUTF();
            AppliedCut cut = new AppliedCut(input.readUTF(), input.readLong(), input.readLong());
            if (cut.revision() < 0
                    || cut.memberCount() < 0
                    || cut.memberCount() > config.expectedInsertions())
                throw new IOException("Invalid membership snapshot cut");
            // Guava allocates its word array from an encoded length. Validate it before readFrom.
            input.mark(6);
            input.readByte();
            input.readByte();
            int words = input.readInt();
            if (words < 1
                    || (long) words * 8 != input.available()
                    || (long) words * 8 > config.filterBytes())
                throw new IOException("Invalid membership snapshot bit array length");
            input.reset();
            BloomFilter<RouteBloomKey> filter =
                    BloomFilter.readFrom(input, RouteAddressFunnel.INSTANCE);
            if (input.read() != -1
                    || filter.expectedFpp() > config.maximumFalsePositiveProbability())
                throw new IOException("Invalid membership snapshot contents");
            return new Snapshot(namespace, cut, filter);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid membership snapshot", invalid);
        }
    }

    void write(Snapshot snapshot) throws IOException {
        if (config.snapshotPath().isBlank()) return;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeInt(1);
            out.writeLong(config.expectedInsertions());
            out.writeDouble(config.falsePositiveProbability());
            out.writeUTF(snapshot.namespace());
            out.writeUTF(snapshot.cut().generation());
            out.writeLong(snapshot.cut().revision());
            out.writeLong(snapshot.cut().memberCount());
            snapshot.filter().writeTo(out);
        }
        byte[] payload = buffer.toByteArray();
        if (payload.length > config.filterBytes() + 4096)
            throw new IOException("Membership snapshot exceeds budget");
        Path target = Path.of(config.snapshotPath()).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(temporary)) {
            out.write(payload);
            out.write(digest(payload));
        }
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
