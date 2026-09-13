package com.jupiter.shortlink.agent.riskprofile.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record ShortLinkRiskMetrics(
        long pv2h,
        long uv2h,
        long pv24h,
        long uv24h,
        long pv7d,
        long uv7d,
        Double pvGrowth2hVs24hAvg,
        Double topIpShare,
        Double topVisitorShare,
        Double topRegionShare,
        Double topDeviceShare,
        Double topBrowserShare,
        Double pvPerUv,
        Double peakHourShare,
        Double repeatVisitRatio,
        @JsonDeserialize(using = DimensionWindowsDeserializer.class)
        Map<String, RiskWindowDimensions> dimensionWindows) {
    public ShortLinkRiskMetrics {
        dimensionWindows = dimensionWindows == null ? Map.of() : Map.copyOf(dimensionWindows);
        if (!Set.of("2h", "24h", "7d").containsAll(dimensionWindows.keySet()))
            throw new IllegalArgumentException("Unknown risk dimension window");
        dimensionWindows.forEach((name, dimensions) -> {
            if (!name.equals(dimensions.window())) throw new IllegalArgumentException("Dimension window mismatch");
        });
    }

    /** Preserves existing callers and historical JSON without explanatory dimensions. */
    public ShortLinkRiskMetrics(long pv2h, long uv2h, long pv24h, long uv24h, long pv7d, long uv7d,
            Double pvGrowth2hVs24hAvg, Double topIpShare, Double topVisitorShare, Double topRegionShare,
            Double topDeviceShare, Double topBrowserShare, Double pvPerUv, Double peakHourShare,
            Double repeatVisitRatio) {
        this(pv2h, uv2h, pv24h, uv24h, pv7d, uv7d, pvGrowth2hVs24hAvg, topIpShare, topVisitorShare,
                topRegionShare, topDeviceShare, topBrowserShare, pvPerUv, peakHourShare, repeatVisitRatio, Map.of());
    }

    public ShortLinkRiskMetrics withDimensionWindows(Map<String, RiskWindowDimensions> dimensions) {
        return new ShortLinkRiskMetrics(pv2h, uv2h, pv24h, uv24h, pv7d, uv7d, pvGrowth2hVs24hAvg,
                topIpShare, topVisitorShare, topRegionShare, topDeviceShare, topBrowserShare, pvPerUv,
                peakHourShare, repeatVisitRatio, dimensions);
    }

    /** The graph library's global Map reader erases value types; preserve this field's fixed DTO. */
    public static final class DimensionWindowsDeserializer
            extends JsonDeserializer<Map<String, RiskWindowDimensions>> {
        @Override
        public Map<String, RiskWindowDimensions> deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (node == null || node.isNull()) return Map.of();
            if (!node.isObject() || node.size() > 3)
                throw JsonMappingException.from(parser, "Invalid dimension window object");
            Map<String, RiskWindowDimensions> result = new LinkedHashMap<>();
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!Set.of("2h", "24h", "7d").contains(field.getKey()) || !field.getValue().isObject())
                    throw JsonMappingException.from(parser, "Invalid dimension window entry");
                result.put(field.getKey(), parser.getCodec().treeToValue(field.getValue(), RiskWindowDimensions.class));
            }
            return Map.copyOf(result);
        }
    }
}
