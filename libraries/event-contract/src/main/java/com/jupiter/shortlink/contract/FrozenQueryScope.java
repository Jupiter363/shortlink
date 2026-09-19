package com.jupiter.shortlink.contract;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Closed cross-service proof of one deterministic shard, never a claim that the parent was read. */
public record FrozenQueryScope(String schemaVersion, String scopeKind, String parentScopeRef,
        String parentMemberHash, long parentMemberCount, String enumerationVersion,
        String shardId, int shardIndex, int shardCount, String shardMemberHash, List<Long> linkIds) {
    public static final String SCHEMA = "frozen-query-scope/v1";
    public static final String PROOF_SCHEMA = "frozen-scope-proof/v1";
    public static final int SHARD_SIZE = 500;
    private static final Set<String> FIELDS = Set.of("schemaVersion", "scopeKind", "parentScopeRef", "parentMemberHash",
            "parentMemberCount", "enumerationVersion", "shardId", "shardIndex", "shardCount", "shardMemberHash", "linkIds");

    public FrozenQueryScope {
        require(SCHEMA.equals(schemaVersion) && "FROZEN_SET".equals(scopeKind));
        require(reference(parentScopeRef) && hash(parentMemberHash) && hash(enumerationVersion));
        require(parentMemberCount > 0 && shardIndex >= 0 && shardCount > 0 && shardIndex < shardCount);
        require(parentMemberCount / SHARD_SIZE + (parentMemberCount % SHARD_SIZE == 0 ? 0 : 1) == shardCount);
        linkIds = validatedMembers(linkIds);
        long expected = Math.min(SHARD_SIZE, parentMemberCount - (long) shardIndex * SHARD_SIZE);
        require(linkIds.size() == expected && hash(shardMemberHash) && memberHash(linkIds).equals(shardMemberHash));
        require(shardIdFor(parentScopeRef, shardIndex, shardMemberHash).equals(shardId));
        if (shardCount == 1) require(parentMemberHash.equals(shardMemberHash));
    }

    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw invalid(); }

    /** Ascending unique IDs; an empty selection is valid for authorization but never creates a job shard. */
    public static List<Long> validatedMembers(List<Long> values) {
        require(values != null && values.size() <= SHARD_SIZE);
        long previous = 0;
        for (Long id : values) { require(id != null && id > previous); previous = id; }
        return List.copyOf(values);
    }

    /** Canonical hash also works for a complete parent collection larger than one shard. */
    public static String memberHash(List<Long> values) {
        require(values != null);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("analytics-members/v1\n".getBytes(StandardCharsets.UTF_8));
            long previous = 0;
            for (Long id : values) {
                require(id != null && id > previous); previous = id;
                digest.update((Long.toString(id) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String shardIdFor(String scopeRef, int index, String members) {
        require(reference(scopeRef) && index >= 0 && hash(members));
        return "shard-" + digest(scopeRef + "\n" + index + "\n" + members);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion); result.put("scopeKind", scopeKind);
        result.put("parentScopeRef", parentScopeRef); result.put("parentMemberHash", parentMemberHash);
        result.put("parentMemberCount", parentMemberCount); result.put("enumerationVersion", enumerationVersion);
        result.put("shardId", shardId); result.put("shardIndex", shardIndex); result.put("shardCount", shardCount);
        result.put("shardMemberHash", shardMemberHash); result.put("linkIds", linkIds);
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> proof(String authorizedSelectionVersion) {
        require(hash(authorizedSelectionVersion));
        Map<String, Object> result = new LinkedHashMap<>(asMap());
        result.put("schemaVersion", PROOF_SCHEMA);
        result.put("authorizedSelectionVersion", authorizedSelectionVersion);
        result.put("shardComplete", true);
        result.put("parentComplete", false);
        return Collections.unmodifiableMap(result);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static FrozenQueryScope fromMap(Map<?, ?> values) {
        require(values != null && FIELDS.equals(values.keySet()));
        Object rawIds = values.get("linkIds");
        require(rawIds instanceof List<?>);
        List<Long> ids = ((List<?>) rawIds).stream().map(FrozenQueryScope::integer).toList();
        return new FrozenQueryScope(string(values.get("schemaVersion")), string(values.get("scopeKind")),
                string(values.get("parentScopeRef")), string(values.get("parentMemberHash")), integer(values.get("parentMemberCount")),
                string(values.get("enumerationVersion")), string(values.get("shardId")),
                exactInt(values.get("shardIndex")), exactInt(values.get("shardCount")), string(values.get("shardMemberHash")), ids);
    }

    public static FrozenQueryScope fromProof(Map<?, ?> proof) {
        require(proof != null && proof.size() == FIELDS.size() + 3 && proof.keySet().containsAll(FIELDS)
                && PROOF_SCHEMA.equals(proof.get("schemaVersion")) && Boolean.TRUE.equals(proof.get("shardComplete"))
                && Boolean.FALSE.equals(proof.get("parentComplete"))
                && hash(string(proof.get("authorizedSelectionVersion"))));
        Map<String, Object> scope = new LinkedHashMap<>();
        for (String field : FIELDS) scope.put(field, proof.get(field));
        scope.put("schemaVersion", SCHEMA);
        return fromMap(scope);
    }

    private static int exactInt(Object value) { long n = integer(value); require(n <= Integer.MAX_VALUE); return (int) n; }
    private static long integer(Object value) {
        require(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger);
        try { long n = new java.math.BigInteger(value.toString()).longValueExact(); require(n >= 0); return n; }
        catch (ArithmeticException invalid) { throw invalid(); }
    }
    private static String string(Object value) { require(value instanceof String); return (String) value; }
    private static boolean reference(String value) { return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"); }
    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("FROZEN_SCOPE_INVALID"); }
}
