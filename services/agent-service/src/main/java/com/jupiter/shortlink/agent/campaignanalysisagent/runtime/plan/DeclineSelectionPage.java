package com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore.Artifact;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Comparability;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Metric;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignLinkComparability.Result;
import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.plan.CampaignParentCoverage.Gap;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded immutable comparison pages and a small lineage manifest; no runner or authorization. */
public final class DeclineSelectionPage {
    public static final String PAGE_TYPE = "DeclinePageArtifact";
    public static final String PAGE_SCHEMA = "campaign.decline-page/v1";
    public static final String CHAIN_TYPE = "DeclineChainArtifact";
    public static final String CHAIN_SCHEMA = "campaign.decline-chain/v1";
    private static final int SHARD_SIZE = 500;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .build();

    static {
        JSON.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    private DeclineSelectionPage() {}

    public record Definition(String collectionId, String scopeArtifactId, String scopeRef,
                             String periodsRef, Metric metric, int shardCount, long memberCount) {
        public Definition {
            text(collectionId, "DECLINE_COLLECTION_ID_REQUIRED");
            text(scopeArtifactId, "DECLINE_SCOPE_ARTIFACT_REQUIRED");
            text(scopeRef, "DECLINE_SCOPE_REFERENCE_REQUIRED");
            text(periodsRef, "DECLINE_PERIODS_REFERENCE_REQUIRED");
            require(metric != null && memberCount >= 0 && shardCount >= 0, "DECLINE_DEFINITION_INVALID");
            long expectedShards = memberCount / SHARD_SIZE + (memberCount % SHARD_SIZE == 0 ? 0 : 1);
            require(expectedShards == shardCount, "DECLINE_SHARD_COUNT_MISMATCH");
        }
    }

    public record Page(Definition definition, int shardIndex, List<Result> rows, List<Gap> gaps) {
        public Page {
            require(rows != null && gaps != null, "DECLINE_PAGE_LISTS_REQUIRED");
            require(rows.size() <= SHARD_SIZE && gaps.size() <= 2, "DECLINE_PAGE_TOO_LARGE");
            rows = List.copyOf(rows);
            gaps = List.copyOf(gaps);
            validatePage(definition, shardIndex, rows, gaps);
        }
    }

    public record Counts(long compared, long selected, long unverified, long incompatible, int gapCount) {
        public Counts {
            require(compared >= 0 && selected >= 0 && unverified >= 0 && incompatible >= 0 && gapCount >= 0,
                    "DECLINE_COUNTS_INVALID");
            require(selected <= compared && unverified <= compared - selected
                    && incompatible <= compared - selected - unverified, "DECLINE_COUNTS_INCONSISTENT");
        }
    }

    public record Chain(Definition definition, int pageCount, String previousArtifactId,
                        String previousPayloadHash, String pageArtifactId, String pagePayloadHash,
                        Counts totals, String chainHash) {
        public Chain {
            require(definition != null && totals != null, "DECLINE_CHAIN_FIELDS_REQUIRED");
            require(pageCount >= 1 && pageCount <= Math.max(1, definition.shardCount()), "DECLINE_CHAIN_PAGE_COUNT_INVALID");
            require(previousArtifactId != null && previousPayloadHash != null, "DECLINE_PREVIOUS_REFERENCE_REQUIRED");
            text(pageArtifactId, "DECLINE_PAGE_ARTIFACT_REQUIRED");
            hashValue(pagePayloadHash, "DECLINE_PAGE_HASH_INVALID");
            hashValue(chainHash, "DECLINE_CHAIN_HASH_INVALID");
            if (pageCount == 1) {
                require(previousArtifactId.isEmpty() && previousPayloadHash.isEmpty(), "DECLINE_FIRST_CHAIN_HAS_PREVIOUS");
                require(chainHash.equals(CampaignRunStore.sha256(seed(definition) + ':' + pagePayloadHash)),
                        "DECLINE_CHAIN_HASH_MISMATCH");
            } else {
                text(previousArtifactId, "DECLINE_PREVIOUS_REFERENCE_REQUIRED");
                hashValue(previousPayloadHash, "DECLINE_PREVIOUS_HASH_INVALID");
                require(!previousArtifactId.equals(pageArtifactId), "DECLINE_CHAIN_REFERENCE_COLLISION");
            }
            long coveredCandidates = Math.min(definition.memberCount(), (long) pageCount * SHARD_SIZE);
            require(totals.compared() <= coveredCandidates && totals.gapCount() <= (long) pageCount * 2,
                    "DECLINE_CHAIN_COUNTS_INVALID");
            if (totals.gapCount() == 0) {
                require(totals.compared() == coveredCandidates, "DECLINE_CHAIN_COVERAGE_MISMATCH");
            } else {
                require(coveredCandidates > 0 && totals.compared() < coveredCandidates, "DECLINE_CHAIN_GAPS_INVALID");
            }
        }
    }

    public static void validate(Page page) {
        require(page != null, "DECLINE_PAGE_REQUIRED");
        validatePage(page.definition(), page.shardIndex(), page.rows(), page.gaps());
    }

    public static String encode(Page page) { validate(page); return write(page); }
    public static Page decode(String encoded) { return read(encoded, Page.class); }
    public static String hash(Page page) { return CampaignRunStore.sha256(encode(page)); }

    /** Returns every structurally verified decline in this shard; never a top-K sample. */
    public static List<Result> selected(Page page) {
        validate(page);
        return page.rows().stream()
                .filter(row -> row.comparability() == Comparability.VERIFIED && row.delta().signum() < 0)
                .sorted(Comparator.comparing(Result::delta).thenComparingLong(Result::linkId)).toList();
    }

    public static Counts counts(Page page) {
        validate(page);
        return new Counts(page.rows().size(), selected(page).size(),
                page.rows().stream().filter(row -> row.comparability() == Comparability.UNVERIFIED).count(),
                page.rows().stream().filter(row -> row.comparability() == Comparability.INCOMPATIBLE).count(),
                page.gaps().size());
    }

    /**
     * The caller authorizes the previous Artifact and publishes the current page using encode().
     * This checks its actual payload hash and lineage without loading any older pages or claiming
     * that a supplied reference has already been published by a store.
     */
    public static Chain advance(Page page, String pageArtifactId, Artifact previous) {
        validate(page);
        text(pageArtifactId, "DECLINE_PAGE_ARTIFACT_REQUIRED");
        String previousId = "";
        String previousHash = "";
        String predecessor = seed(page.definition());
        Counts totals = counts(page);
        if (previous == null) {
            require(page.shardIndex() == 0, "DECLINE_PREVIOUS_CHAIN_REQUIRED");
        } else {
            require(previous.metadata() != null && previous.metadata().ref() != null && previous.payloadJson() != null,
                    "DECLINE_PREVIOUS_ARTIFACT_INVALID");
            var ref = previous.metadata().ref();
            require(CHAIN_TYPE.equals(ref.type()) && CHAIN_SCHEMA.equals(ref.schemaVersion())
                    && page.definition().scopeRef().equals(ref.scopeRef())
                    && page.definition().periodsRef().equals(ref.periodsRef()), "DECLINE_PREVIOUS_ARTIFACT_MISMATCH");
            require(Objects.equals(ref.payloadHash(), CampaignRunStore.sha256(previous.payloadJson())),
                    "DECLINE_PREVIOUS_PAYLOAD_CORRUPTED");
            Chain preceding = decodeChain(previous.payloadJson());
            require(page.definition().equals(preceding.definition()), "DECLINE_DEFINITION_CHANGED");
            require(preceding.pageCount() == page.shardIndex(), "DECLINE_CHAIN_NOT_ADJACENT");
            previousId = ref.artifactId();
            previousHash = ref.payloadHash();
            predecessor = preceding.chainHash();
            totals = add(preceding.totals(), totals);
        }
        String pageHash = hash(page);
        return new Chain(page.definition(), Math.addExact(page.shardIndex(), 1), previousId, previousHash,
                pageArtifactId, pageHash, totals, CampaignRunStore.sha256(predecessor + ':' + pageHash));
    }

    public static String encodeChain(Chain chain) {
        require(chain != null, "DECLINE_CHAIN_REQUIRED");
        return write(chain);
    }
    public static Chain decodeChain(String encoded) { return read(encoded, Chain.class); }

    public static boolean selectionComplete(Chain chain) {
        require(chain != null, "DECLINE_CHAIN_REQUIRED");
        return chain.pageCount() == Math.max(1, chain.definition().shardCount())
                && chain.totals().gapCount() == 0 && chain.totals().compared() == chain.definition().memberCount();
    }

    public static String emptyReason(Chain chain) {
        require(chain != null, "DECLINE_CHAIN_REQUIRED");
        if (chain.totals().selected() > 0) return null;
        return selectionComplete(chain) && chain.totals().unverified() == 0 && chain.totals().incompatible() == 0
                ? "NO_DECLINES" : "INSUFFICIENT_EVIDENCE";
    }

    private static void validatePage(Definition definition, int shardIndex, List<Result> rows, List<Gap> gaps) {
        require(definition != null, "DECLINE_DEFINITION_REQUIRED");
        require(shardIndex >= 0 && shardIndex < Math.max(1, definition.shardCount()), "DECLINE_SHARD_INDEX_INVALID");
        require(rows.size() <= SHARD_SIZE && gaps.size() <= 2, "DECLINE_PAGE_TOO_LARGE");
        if (definition.memberCount() == 0) {
            require(shardIndex == 0 && rows.isEmpty() && gaps.isEmpty(), "DECLINE_EMPTY_SCOPE_INVALID");
            return;
        }
        long candidates = Math.min(SHARD_SIZE, definition.memberCount() - (long) shardIndex * SHARD_SIZE);
        if (gaps.isEmpty()) require(rows.size() == candidates, "DECLINE_CANDIDATE_COUNT_MISMATCH");
        else require(rows.isEmpty(), "DECLINE_GAP_PAGE_HAS_ROWS");
        Set<Integer> periods = new HashSet<>();
        for (Gap gap : gaps) {
            require(gap != null && gap.shardIndex() == shardIndex && (gap.periodIndex() == 0 || gap.periodIndex() == 1)
                    && periods.add(gap.periodIndex()), "DECLINE_GAP_IDENTITY_INVALID");
            text(gap.code(), "DECLINE_GAP_CODE_REQUIRED");
        }
        Set<Long> links = new HashSet<>();
        for (Result row : rows) {
            require(row != null && row.linkId() > 0 && links.add(row.linkId()), "DECLINE_LINK_ID_INVALID");
            require(row.metric() == definition.metric() && row.baseline() >= 0 && row.target() >= 0
                    && row.comparability() != null && row.explanation() != null, "DECLINE_ROW_INVALID");
            BigInteger expected = BigInteger.valueOf(row.target()).subtract(BigInteger.valueOf(row.baseline()));
            require(expected.equals(row.delta()), "DECLINE_DELTA_MISMATCH");
            if (row.comparability() == Comparability.VERIFIED && row.baseline() > 0) {
                BigDecimal rate = new BigDecimal(expected).divide(BigDecimal.valueOf(row.baseline()), MathContext.DECIMAL128);
                require(row.rate() != null && rate.compareTo(row.rate()) == 0, "DECLINE_RATE_MISMATCH");
            } else {
                require(row.rate() == null, "DECLINE_RATE_NOT_APPLICABLE");
            }
        }
    }

    private static Counts add(Counts left, Counts right) {
        try {
            return new Counts(Math.addExact(left.compared(), right.compared()), Math.addExact(left.selected(), right.selected()),
                    Math.addExact(left.unverified(), right.unverified()), Math.addExact(left.incompatible(), right.incompatible()),
                    Math.addExact(left.gapCount(), right.gapCount()));
        } catch (ArithmeticException overflow) { throw new IllegalArgumentException("DECLINE_COUNTS_OVERFLOW", overflow); }
    }

    private static String seed(Definition definition) { return CampaignRunStore.sha256(write(definition)); }
    private static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("DECLINE_JSON_INVALID", invalid); }
    }
    private static <T> T read(String encoded, Class<T> type) {
        require(encoded != null, "DECLINE_JSON_REQUIRED");
        try {
            T value = JSON.readValue(encoded, type);
            require(value != null, "DECLINE_JSON_INVALID");
            return value;
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("DECLINE_JSON_INVALID", invalid); }
    }
    private static void hashValue(String value, String code) { require(value != null && value.matches("[a-f0-9]{64}"), code); }
    private static void text(String value, String code) { require(value != null && !value.isBlank(), code); }
    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }
}
