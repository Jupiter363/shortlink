package com.nageoffer.shortlink.agent.riskprofile.source;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ShortLinkBusinessRiskStatsGateway implements RiskStatsSourceGateway {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token";

    private static final String USERNAME_HEADER = "X-Agent-Username";

    private final AgentProperties agentProperties;

    private final RestTemplate restTemplate;

    @Autowired
    public ShortLinkBusinessRiskStatsGateway(AgentProperties agentProperties, RestTemplateBuilder restTemplateBuilder) {
        this(agentProperties, restTemplateBuilder.build());
    }

    public ShortLinkBusinessRiskStatsGateway(AgentProperties agentProperties, RestTemplate restTemplate) {
        this.agentProperties = agentProperties;
        this.restTemplate = restTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ShortLinkActiveCandidate> listActiveShortLinks(Instant since) {
        Object data = get(
                "/internal/short-link-admin/v1/agent-tools/risk/active-short-links",
                orderedParams("since", since.toString())
        );
        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException("Risk stats active short links data must be a list");
        }
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> activeCandidate((Map<String, Object>) row))
                .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ShortLinkStatsWindow loadStatsWindow(ShortLinkActiveCandidate candidate, Instant start, Instant end) {
        Object data = get(
                "/internal/short-link-admin/v1/agent-tools/risk/short-link-window-stats",
                orderedParams(
                        "gid", candidate.gid(),
                        "fullShortUrl", candidate.fullShortUrl(),
                        "startTime", start.toString(),
                        "endTime", end.toString()
                )
        );
        if (!(data instanceof Map<?, ?> row)) {
            throw new IllegalStateException("Risk stats window data must be an object");
        }
        return statsWindow((Map<String, Object>) row, candidate, start, end);
    }

    private Object get(String path, Map<String, Object> queryParams) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    uri(path, queryParams),
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    Map.class
            );
            Map<?, ?> body = response.getBody();
            if (body == null) {
                throw new IllegalStateException("Risk stats API returned empty response");
            }
            if (!isSuccess(body)) {
                throw new IllegalStateException(message(body));
            }
            return body.get("data");
        } catch (RestClientException ex) {
            throw new IllegalStateException("Risk stats API request failed: " + ex.getMessage(), ex);
        }
    }

    private URI uri(String path, Map<String, Object> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl() + normalizePath(path));
        queryParams.forEach((key, value) -> {
            if (value != null) {
                builder.queryParam(key, value);
            }
        });
        return builder.build(true).toUri();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        String username = agentProperties.getBusiness().getUsername();
        if (!StringUtils.hasText(username)) {
            throw new IllegalStateException("Risk stats business username is required");
        }
        headers.add(USERNAME_HEADER, username);
        String internalToken = agentProperties.getBusiness().getInternalToken();
        if (StringUtils.hasText(internalToken)) {
            headers.add(INTERNAL_TOKEN_HEADER, internalToken);
        }
        return headers;
    }

    private String baseUrl() {
        String baseUrl = agentProperties.getBusiness().getBaseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private boolean isSuccess(Map<?, ?> body) {
        Object success = body.get("success");
        Object code = body.get("code");
        return Boolean.TRUE.equals(success) || "0".equals(String.valueOf(code));
    }

    private String message(Map<?, ?> body) {
        Object message = body.get("message");
        Object code = body.get("code");
        if (message != null) {
            return String.valueOf(message);
        }
        return "Risk stats API failed with code " + code;
    }

    private Map<String, Object> orderedParams(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            params.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return params;
    }

    private ShortLinkActiveCandidate activeCandidate(Map<String, Object> row) {
        return new ShortLinkActiveCandidate(
                stringValue(row.get("gid")),
                stringValue(row.get("domain")),
                stringValue(row.get("shortUri")),
                stringValue(row.get("fullShortUrl")),
                intValue(row.get("pv")),
                intValue(row.get("uv")),
                intValue(row.get("uip"))
        );
    }

    private ShortLinkStatsWindow statsWindow(
            Map<String, Object> row,
            ShortLinkActiveCandidate candidate,
            Instant start,
            Instant end
    ) {
        return new ShortLinkStatsWindow(
                stringOrDefault(row.get("gid"), candidate.gid()),
                stringOrDefault(row.get("domain"), candidate.domain()),
                stringOrDefault(row.get("shortUri"), candidate.shortUri()),
                stringOrDefault(row.get("fullShortUrl"), candidate.fullShortUrl()),
                start,
                end,
                intValue(row.get("pv")),
                intValue(row.get("uv")),
                intValue(row.get("uip")),
                doubleValue(row.get("topIpShare")),
                doubleValue(row.get("topVisitorShare")),
                doubleValue(row.get("topRegionShare")),
                doubleValue(row.get("topDeviceShare")),
                doubleValue(row.get("topBrowserShare")),
                doubleValue(row.get("peakHourShare")),
                doubleValue(row.get("repeatVisitRatio"))
        );
    }

    private String stringOrDefault(Object value, String fallback) {
        String stringValue = stringValue(value);
        return StringUtils.hasText(stringValue) ? stringValue : fallback;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}
