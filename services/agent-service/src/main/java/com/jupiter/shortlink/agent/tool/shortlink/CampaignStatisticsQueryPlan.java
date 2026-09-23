package com.jupiter.shortlink.agent.tool.shortlink;

import com.jupiter.shortlink.agent.campaignanalysisagent.runtime.persistence.CampaignRunStore;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable multi-object/multi-period query plan used to bind continuation references. */
public final class CampaignStatisticsQueryPlan {
    private CampaignStatisticsQueryPlan() {}

    public record Scope(String gid, String fullShortUrl, String label) {
        public Scope {
            text(gid, "Missing required argument: gid");
            fullShortUrl = normalize(fullShortUrl);
            label = textOrEmpty(label);
        }
        public String key() { return gid + "|" + fullShortUrl; }
    }

    public record Period(LocalDate startDate, LocalDate endDate, String label) {
        public Period {
            Objects.requireNonNull(startDate, "Invalid statistics calendar date");
            Objects.requireNonNull(endDate, "Invalid statistics calendar date");
            long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            if (days <= 0 || days > 180) throw new IllegalArgumentException("Statistics periods must contain one to 180 inclusive days");
            label = textOrEmpty(label);
        }
        public String key() { return startDate + ":" + endDate; }
    }

    public record Query(Scope scope, Period period, String key, Map<String, Object> arguments) {
        public Query {
            Objects.requireNonNull(scope); Objects.requireNonNull(period); text(key, "QUERY_KEY_REQUIRED");
            arguments = immutable(arguments);
        }
    }

    public record Plan(String planId, List<Scope> scopes, List<Period> periods, List<Query> queries) {
        public Plan {
            text(planId, "QUERY_PLAN_ID_REQUIRED");
            scopes = List.copyOf(scopes); periods = List.copyOf(periods); queries = List.copyOf(queries);
        }
        public int combinations() { return queries.size(); }
    }

    public static Plan create(List<Map<String, Object>> scopeValues, List<Map<String, Object>> periodValues) {
        List<Scope> scopes = scopes(scopeValues);
        List<Period> periods = periods(periodValues);
        long combinations = Math.multiplyExact(scopes.size(), periods.size());
        if (combinations < 2 || combinations > 16)
            throw new IllegalArgumentException("Comparison requires two to sixteen object/period combinations");
        List<Query> queries = new ArrayList<>();
        for (Scope scope : scopes) for (Period period : periods) {
            String key = scope.key() + "|" + period.startDate() + "|" + period.endDate();
            queries.add(new Query(scope, period, key, query(scope, period)));
        }
        // The first period and first scope are comparison baselines. Their order therefore
        // belongs to the continuation identity even when the set of queries is unchanged.
        // Length prefixes keep arbitrary gid/URL text from colliding with field separators.
        StringBuilder canonical = new StringBuilder("comparison-query-plan/v2");
        append(canonical, Integer.toString(scopes.size()));
        for (Scope scope : scopes) {
            append(canonical, scope.gid());
            append(canonical, scope.fullShortUrl());
        }
        append(canonical, Integer.toString(periods.size()));
        for (Period period : periods) {
            append(canonical, period.startDate().toString());
            append(canonical, period.endDate().toString());
        }
        String planId = CampaignRunStore.sha256(canonical.toString());
        return new Plan(planId, scopes, periods, queries);
    }

    private static void append(StringBuilder canonical, String value) {
        canonical.append(value.length()).append(':').append(value);
    }

    private static List<Scope> scopes(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty() || values.size() > 16) throw new IllegalArgumentException("One to sixteen scopes are required");
        List<Scope> result = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> value : values) {
            if (value == null) throw new IllegalArgumentException("Invalid scope");
            Scope scope = new Scope(text(value.get("gid")), textOrEmpty(value.get("fullShortUrl")), textOrEmpty(value.get("label")));
            if (!seen.add(scope.key())) throw new IllegalArgumentException("Duplicate comparison scope");
            result.add(scope);
        }
        return List.copyOf(result);
    }

    private static List<Period> periods(List<Map<String, Object>> values) {
        if (values == null || values.isEmpty() || values.size() > 16) throw new IllegalArgumentException("One to sixteen periods are required");
        List<Period> result = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> value : values) {
            if (value == null) throw new IllegalArgumentException("Invalid period");
            LocalDate start, end;
            try { start = LocalDate.parse(text(value.get("startDate"))); end = LocalDate.parse(text(value.get("endDate"))); }
            catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid statistics calendar date"); }
            Period period = new Period(start, end, textOrEmpty(value.get("label")));
            if (!seen.add(period.key())) throw new IllegalArgumentException("Duplicate comparison period");
            result.add(period);
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> query(Scope scope, Period period) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gid", scope.gid());
        if (!scope.fullShortUrl().isEmpty()) result.put("fullShortUrl", scope.fullShortUrl());
        result.put("startDate", period.startDate().toString());
        result.put("endDate", period.endDate().toString());
        return immutable(result);
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        if (value == null) return Map.of();
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
    private static String normalize(Object value) { return textOrEmpty(value).replaceFirst("(?i)^https?://", ""); }
    private static void text(String value, String code) {
        if (value == null || value.isBlank() || value.length() > 128) throw new IllegalArgumentException(code);
    }

    private static String text(Object value) { String result = textOrEmpty(value); if (result.isEmpty()) throw new IllegalArgumentException("Missing required argument: gid"); return result; }
    private static String textOrEmpty(Object value) { return value == null ? "" : value.toString().trim(); }
}
