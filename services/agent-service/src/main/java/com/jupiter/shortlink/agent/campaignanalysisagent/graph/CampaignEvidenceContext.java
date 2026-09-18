package com.jupiter.shortlink.agent.campaignanalysisagent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounds internal provenance after tool validation, without truncating business evidence. */
final class CampaignEvidenceContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PROVENANCE_KEYS =
            Set.of("sourceCut", "manifestVersion", "windowVersions");
    private static final List<String> AUDIT_KEYS =
            List.of("snapshotId", "jobId", "recoveryEpoch", "manifestSelectionHash");
    private static final int INLINE_ENTRIES = 64;
    private static final int INLINE_BYTES = 4096;

    private CampaignEvidenceContext() {}

    static Object compact(Object value) {
        if (!(value instanceof Map<?, ?> data)) return value;
        var fingerprints = new IdentityHashMap<Map<?, ?>, Fingerprint>();
        Map<String, Object> result = copy(data);
        result.computeIfPresent("meta", (key, meta) -> compactMetadata(meta, data, fingerprints));
        // These are the documented metadata locations; a business dimension called
        // sourceCut, or an unknown extension field, must not be rewritten recursively.
        if (("ranking".equals(data.get("type")) || "comparison".equals(data.get("type")))
                && data.get("rows") instanceof List<?> rows) {
            List<Object> compactRows = new ArrayList<>(rows.size());
            for (Object item : rows) {
                if (item instanceof Map<?, ?> row && row.containsKey("quality")) {
                    var compactRow = copy(row);
                    compactRow.put("quality", compactMetadata(row.get("quality"), data, fingerprints));
                    compactRows.add(compactRow);
                } else compactRows.add(item);
            }
            result.put("rows", compactRows);
        }
        return result;
    }

    private static Object compactMetadata(Object value, Map<?, ?> data,
            IdentityHashMap<Map<?, ?>, Fingerprint> fingerprints) {
        if (!(value instanceof Map<?, ?> metadata)) return value;
        var result = copy(metadata);
        for (String key : PROVENANCE_KEYS) {
            if (!(metadata.get(key) instanceof Map<?, ?> provenance)
                    || Boolean.TRUE.equals(provenance.get("compacted"))) continue;
            Fingerprint fingerprint = fingerprints.computeIfAbsent(provenance, CampaignEvidenceContext::fingerprint);
            if (provenance.size() < INLINE_ENTRIES && fingerprint.bytes() <= INLINE_BYTES) continue;
            Map<String, Object> auditReference = new LinkedHashMap<>();
            for (String auditKey : AUDIT_KEYS) {
                Object reference = metadata.containsKey(auditKey) ? metadata.get(auditKey) : data.get(auditKey);
                if (reference != null) auditReference.put(auditKey, reference);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("compacted", true);
            summary.put("kind", "INTERNAL_PROVENANCE_SUMMARY");
            summary.put("entryCount", provenance.size());
            summary.put("originalJsonBytes", fingerprint.bytes());
            summary.put("sha256", fingerprint.sha256());
            summary.put("auditReference", auditReference);
            summary.put("description", "仅压缩内部来源明细，未裁剪统计数据；可通过快照或任务引用核查服务侧记录。条目数不代表访问量。");
            result.put(key, summary);
        }
        return result;
    }

    private static Map<String, Object> copy(Map<?, ?> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Fingerprint fingerprint(Map<?, ?> provenance) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var sink = new DigestSink(digest);
            // Stream to a digest instead of allocating another full provenance JSON string.
            JSON.writeValue(sink, provenance);
            return new Fingerprint(sink.bytes, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("统计来源摘要生成失败", exception);
        }
    }

    private record Fingerprint(long bytes, String sha256) {}

    private static final class DigestSink extends OutputStream {
        private final MessageDigest digest;
        private long bytes;

        private DigestSink(MessageDigest digest) { this.digest = digest; }

        @Override public void write(int value) {
            digest.update((byte) value);
            bytes++;
        }

        @Override public void write(byte[] value, int offset, int length) {
            digest.update(value, offset, length);
            bytes += length;
        }
    }
}
