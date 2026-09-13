package com.jupiter.shortlink.agent.riskprofile.source;

import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;
import com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport;
import com.jupiter.shortlink.agent.riskprofile.model.StatsEvidence;
import com.jupiter.shortlink.agent.riskprofile.model.RiskWindowDimensions;
import com.jupiter.shortlink.agent.harness.security.AgentPrincipal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Scheduled profiles use the same Admin authorization and Analytics snapshots as ordinary tools.
 */
@Component
public class ShortLinkBusinessRiskStatsGateway implements RiskStatsSourceGateway {
    private final AgentProperties properties;
    private final BoundedHttpTransport transport;
    private final RestTemplate testTransport;
    private com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient authority;

    public ShortLinkBusinessRiskStatsGateway(
            AgentProperties properties, BoundedHttpTransport transport) {
        this.properties = properties;
        this.transport = transport;
        this.testTransport = null;
    }

    @Autowired
    public ShortLinkBusinessRiskStatsGateway(
            AgentProperties properties,
            BoundedHttpTransport transport,
            com.jupiter.shortlink.agent.business.shortlink.AgentAuthorityClient authority) {
        this(properties, transport);
        this.authority = authority;
    }

    public ShortLinkBusinessRiskStatsGateway(
            AgentProperties properties, RestTemplate testTransport) {
        this.properties = properties;
        this.transport = null;
        this.testTransport = testTransport;
    }

    @Override
    public List<ShortLinkActiveCandidate> listActiveShortLinks(Instant since) {
        if (authority != null) return listAuthorizedCandidates(since);
        Instant requestedEnd = since.plus(Duration.ofDays(7));
        List<ShortLinkActiveCandidate> candidates = new ArrayList<>();
        String snapshot = null, cursor = null;
        Map<String, Object> originalMeta = null;
        for (int page = 0; page < 20; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("since", since.toString());
            params.put("endTime", requestedEnd.toString());
            params.put("pageSize", 500);
            if (snapshot != null) params.put("snapshotId", snapshot);
            if (cursor != null) params.put("cursor", cursor);
            Map<String, Object> envelope = get("/risk/active-short-links", params);
            Map<String, Object> meta = meta(envelope);
            if (originalMeta != null) StatsEvidence.requireSameSnapshot(originalMeta, meta);
            else originalMeta = meta;
            for (Map<String, Object> row : items(envelope)) {
                candidates.add(
                        new ShortLinkActiveCandidate(
                                text(row.get("gid")),
                                text(row.get("domain")),
                                text(row.get("shortUri")),
                                text(row.get("fullShortUrl")),
                                StatsEvidence.number(row.get("pv")),
                                null,
                                null,
                                text(meta.get("tenantId")),
                                StatsEvidence.number(row.get("linkId")),
                                meta));
            }
            Object next = meta.get("nextCursor");
            if (next == null || next.toString().isBlank()) return List.copyOf(candidates);
            cursor = next.toString();
            snapshot = text(meta.get("snapshotId"));
        }
        throw new IllegalStateException(
                "TOO_LARGE: active candidate snapshot exceeds batch budget");
    }

    private List<ShortLinkActiveCandidate> listAuthorizedCandidates(Instant since) {
        AgentPrincipal scheduler = schedulerPrincipal();
        List<ShortLinkActiveCandidate> candidates = new ArrayList<>();
        String discoveryCursor = null;
        Set<String> seenCursors = new HashSet<>();
        Set<String> seenGroups = new HashSet<>();
        int[] scannedLinks = {0};
        Map<String, Object> discoveryCut = new LinkedHashMap<>();
        for (int page = 0; page < 1000; page++) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("pageSize", 100);
            if (discoveryCursor != null) parameters.put("cursor", discoveryCursor);
            Map<String, Object> response = request("/risk/scheduled-scopes", parameters, scheduler);
            if (response == null || !"0".equals(String.valueOf(response.get("code")))
                    || !(response.get("data") instanceof Map<?, ?> data))
                throw new SecurityException("Scheduled tenant discovery could not be authorized");
            Map<String, Object> discovery = StatsEvidence.copyMap(data);
            List<Map<String, Object>> scopes = items(discovery);
            if (scopes.size() > 1) throw new SecurityException("Scheduled scope page exceeded tenant budget");
            for (Map<String, Object> scope : scopes) {
                AgentPrincipal principal = new AgentPrincipal(text(scope.get("tenantId")),
                        text(scope.get("username")), StatsEvidence.number(scope.get("authVersion")), false);
                if (!(scope.get("gids") instanceof List<?> gids) || gids.size() > 100)
                    throw new SecurityException("Scheduled group page exceeded budget");
                for (Object value : gids) {
                    String gid = text(value);
                    if (!seenGroups.add(principal.tenantId() + ":" + gid))
                        throw new SecurityException("Scheduled group scope repeated");
                    if (seenGroups.size() > 1000)
                        throw new IllegalStateException("TOO_LARGE: candidate discovery exceeds 1000 groups");
                    candidates.addAll(listAuthorizedGroup(since, principal, gid, scannedLinks, discoveryCut));
                }
            }
            Object next = discovery.get("nextCursor");
            if (next == null) return List.copyOf(candidates);
            discoveryCursor = text(next);
            if (!seenCursors.add(discoveryCursor))
                throw new IllegalStateException("Scheduled scope cursor did not advance");
        }
        throw new IllegalStateException("TOO_LARGE: scheduled tenant discovery exceeds page budget");
    }

    private List<ShortLinkActiveCandidate> listAuthorizedGroup(Instant since, AgentPrincipal principal,
            String gid, int[] scannedLinks, Map<String, Object> discoveryCut) {
        List<ShortLinkActiveCandidate> candidates = new ArrayList<>();
        Long after = null;
        String ownershipVersion = null;
        String tenantId = principal.tenantId();
        // A failed or over-budget discovery never returns a silently truncated successful batch.
        for (int scopePage = 0; scopePage < 20; scopePage++) {
            var scope = authority.resolvePage(principal, gid, null, null, after, ownershipVersion);
            if (!tenantId.equals(scope.tenantId())
                    || (ownershipVersion != null && !ownershipVersion.equals(scope.ownershipVersion())))
                throw new SecurityException("Candidate ownership changed during discovery");
            ownershipVersion = scope.ownershipVersion();
            if (scope.links().stream().anyMatch(link -> !gid.equals(link.get("gid"))))
                throw new SecurityException("Candidate group left the authorized scope");
            List<Long> ids =
                    scope.links().stream()
                            .map(link -> StatsEvidence.number(link.get("linkId")))
                            .toList();
            scannedLinks[0] += ids.size();
            if (scannedLinks[0] > 10_000)
                throw new IllegalStateException("TOO_LARGE: candidate discovery exceeds 10000 links");
            if (!ids.isEmpty()) {
                String snapshot = null, cursor = null;
                Map<String, Object> originalMeta = null;
                for (int page = 0; page < 20; page++) {
                    Map<String, Object> request = new LinkedHashMap<>();
                    request.put("linkIds", ids);
                    request.put("since", since.toString());
                    request.put("endTime", since.plus(Duration.ofDays(7)).toString());
                    request.put("snapshotId", snapshot);
                    request.put("cursor", cursor);
                    Map<String, Object> response =
                            transport.exchange(
                                    "POST",
                                    URI.create(
                                            properties
                                                            .getBusiness()
                                                            .getBaseUrl()
                                                            .replaceAll("/+$", "")
                                                    + "/internal/short-link-admin/v1/agent-tools/risk/active-link-query"),
                                    authority.headers(principal),
                                    request);
                    Map<String, Object> envelope = checkedResponse(response);
                    Map<String, Object> metadata = meta(envelope);
                    if (discoveryCut.isEmpty()) discoveryCut.putAll(metadata);
                    else
                        for (String field :
                                List.of(
                                        "recoveryEpoch",
                                        "metricVersion",
                                        "detailDatasetVersion")) {
                            if (discoveryCut.get(field) == null
                                    || !Objects.equals(
                                            discoveryCut.get(field), metadata.get(field)))
                                throw new IllegalStateException(
                                        "Candidate discovery cut changed; restart the batch");
                        }
                    if (!tenantId.equals(metadata.get("tenantId")))
                        throw new SecurityException("Candidate statistics tenant changed");
                    if (originalMeta == null) originalMeta = metadata;
                    else StatsEvidence.requireSameSnapshot(originalMeta, metadata);
                    for (Map<String, Object> row : items(envelope)) {
                        long id = StatsEvidence.number(row.get("linkId"));
                        if (!scope.contains(id, text(row.get("gid"))))
                            throw new SecurityException("Candidate left the authorized scope");
                        candidates.add(
                                new ShortLinkActiveCandidate(
                                        text(row.get("gid")),
                                        text(row.get("domain")),
                                        text(row.get("shortUri")),
                                        text(row.get("fullShortUrl")),
                                        StatsEvidence.number(row.get("pv")),
                                        null,
                                        null,
                                        tenantId,
                                        id,
                                        metadata,
                                        principal));
                    }
                    if (metadata.get("nextCursor") == null) break;
                    if (page == 19)
                        throw new IllegalStateException(
                                "TOO_LARGE: candidate snapshot exceeds page budget");
                    snapshot = text(metadata.get("snapshotId"));
                    cursor = text(metadata.get("nextCursor"));
                }
            }
            if (scope.nextCursor() == null) return List.copyOf(candidates);
            if (after != null && scope.nextCursor() <= after)
                throw new IllegalStateException("Candidate cursor did not advance");
            after = scope.nextCursor();
        }
        throw new IllegalStateException("TOO_LARGE: candidate discovery exceeds 10000 links");
    }

    @Override
    public Map<String, ShortLinkStatsWindow> loadStatsWindows(
            ShortLinkActiveCandidate candidate, Instant end) {
        if (candidate.meta() != null && candidate.meta().get("effectiveEnd") != null)
            end = Instant.ofEpochMilli(StatsEvidence.number(candidate.meta().get("effectiveEnd")));
        Map<String, Object> envelope =
                get(
                        "/risk/short-link-windows",
                        Map.of(
                                "gid",
                                candidate.gid(),
                                "fullShortUrl",
                                candidate.fullShortUrl(),
                                "endTime",
                                end.toString()), candidatePrincipal(candidate));
        Map<String, Object> meta = meta(envelope);
        if (candidate.principal() != null) {
            // Resources have independent snapshots. A profile's three windows share their own
            // frozen snapshot, at the discovery cutoff and within the same dataset/epoch.
            for (String field : List.of("effectiveEnd", "recoveryEpoch", "metricVersion", "detailDatasetVersion")) {
                if (candidate.meta().get(field) == null || !Objects.equals(candidate.meta().get(field), meta.get(field)))
                    throw new IllegalStateException("Candidate statistics " + field + " changed; restart the batch");
            }
        }
        Map<String, ShortLinkStatsWindow> windows = new LinkedHashMap<>();
        for (Map<String, Object> row : items(envelope)) {
            String window = text(row.get("window"));
            if (!List.of("2h", "24h", "7d").contains(window)
                    || windows.put(window, window(row, meta, candidate)) != null) {
                throw new IllegalStateException("Statistics window contract is ambiguous");
            }
        }
        if (!windows.keySet().equals(Set.of("2h", "24h", "7d")))
            throw new IllegalStateException("Statistics windows are incomplete");
        return Map.copyOf(windows);
    }

    @Override
    public ShortLinkStatsWindow loadStatsWindow(
            ShortLinkActiveCandidate candidate, Instant start, Instant end) {
        Map<String, Object> envelope =
                get(
                        "/risk/short-link-window-stats",
                        Map.of(
                                "gid",
                                candidate.gid(),
                                "fullShortUrl",
                                candidate.fullShortUrl(),
                                "startTime",
                                start.toString(),
                                "endTime",
                                end.toString()), candidatePrincipal(candidate));
        List<Map<String, Object>> items = items(envelope);
        if (items.size() != 1)
            throw new IllegalStateException("Expected one statistics resource window");
        return window(items.get(0), meta(envelope), candidate);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path, Map<String, Object> parameters) {
        return get(path, parameters, schedulerPrincipal());
    }

    private Map<String, Object> get(String path, Map<String, Object> parameters, AgentPrincipal principal) {
        return checkedResponse(request(path, parameters, principal));
    }

    private AgentPrincipal candidatePrincipal(ShortLinkActiveCandidate candidate) {
        if (authority == null) return schedulerPrincipal();
        AgentPrincipal principal = candidate.principal();
        if (principal == null || principal.system() || !principal.tenantId().equals(candidate.tenantId()))
            throw new SecurityException("Scheduled candidate has no current delegated tenant identity");
        return principal;
    }

    private AgentPrincipal schedulerPrincipal() {
        String username = properties.getBusiness().getUsername();
        if (username == null || username.isBlank())
            throw new IllegalStateException("Scheduled analytics principal is not configured");
        return AgentPrincipal.system(username);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(String path, Map<String, Object> parameters, AgentPrincipal principal) {
        String username = properties.getBusiness().getUsername();
        String token = properties.getBusiness().getInternalToken();
        if (username == null || username.isBlank() || token == null || token.length() < 24)
            throw new IllegalStateException("Scheduled analytics principal is not configured");
        var uri =
                UriComponentsBuilder.fromHttpUrl(
                        properties.getBusiness().getBaseUrl().replaceAll("/+$", "")
                                + "/internal/short-link-admin/v1/agent-tools"
                                + path);
        parameters.forEach(uri::queryParam);
        URI target = uri.build().encode().toUri();
        Map<String, String> headers = authority != null ? authority.headers(principal) :
                Map.of(
                        "X-Agent-Username",
                        username,
                        "X-Agent-Internal-Token",
                        token,
                        "X-Agent-Principal-Mode",
                        "SYSTEM");
        Map<String, Object> response;
        if (transport != null) response = transport.exchange("GET", target, headers, null);
        else {
            HttpHeaders testHeaders = new HttpHeaders();
            headers.forEach(testHeaders::set);
            response =
                    testTransport
                            .exchange(
                                    target,
                                    HttpMethod.GET,
                                    new HttpEntity<>(testHeaders),
                                    Map.class)
                            .getBody();
        }
        return response;
    }

    private static Map<String, Object> checkedResponse(Map<String, Object> response) {
        if (response == null
                || !"0".equals(String.valueOf(response.get("code")))
                || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("Scheduled analytics source is unavailable");
        }
        Map<String, Object> result = StatsEvidence.copyMap(data);
        Map<String, Object> metadata = meta(result);
        if (!"AVAILABLE".equals(metadata.get("availability"))
                || !"COMPLETE".equals(metadata.get("completeness"))
                || !(metadata.get("snapshotId") instanceof String)
                || !(metadata.get("recoveryEpoch") instanceof String)) {
            throw new IllegalStateException(
                    "Scheduled analytics evidence is not complete and available");
        }
        return result;
    }

    private static ShortLinkStatsWindow window(
            Map<String, Object> row, Map<String, Object> meta, ShortLinkActiveCandidate candidate) {
        String tenant = text(meta.get("tenantId"));
        long linkId = StatsEvidence.number(row.get("linkId"));
        if ((candidate.tenantId() != null && !candidate.tenantId().equals(tenant))
                || (candidate.linkId() != null && candidate.linkId() != linkId)
                || !candidate.gid().equals(row.get("gid")))
            throw new SecurityException("Statistics resource identity changed");
        return new ShortLinkStatsWindow(
                text(row.get("gid")),
                text(row.get("domain")),
                text(row.get("shortUri")),
                text(row.get("fullShortUrl")),
                Instant.ofEpochMilli(StatsEvidence.number(row.get("startInclusive"))),
                Instant.ofEpochMilli(StatsEvidence.number(row.get("endExclusive"))),
                StatsEvidence.number(row.get("pv")),
                StatsEvidence.number(row.get("uv")),
                StatsEvidence.number(row.get("uip")),
                ratio(row.get("topIpShare")),
                ratio(row.get("topVisitorShare")),
                ratio(row.get("topRegionShare")),
                ratio(row.get("topDeviceShare")),
                ratio(row.get("topBrowserShare")),
                ratio(row.get("peakHourShare")),
                ratio(row.get("repeatVisitRatio")),
                tenant,
                linkId,
                meta,
                RiskWindowDimensions.from(row.get("window") instanceof String name ? name : "requested",
                        StatsEvidence.number(row.get("startInclusive")), StatsEvidence.number(row.get("endExclusive")),
                        row, meta));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> envelope) {
        if (!(envelope.get("items") instanceof List<?> values))
            throw new IllegalStateException("Statistics items are missing");
        return values.stream()
                .map(
                        value -> {
                            if (!(value instanceof Map<?, ?> row))
                                throw new IllegalStateException("Statistics item is invalid");
                            return StatsEvidence.copyMap(row);
                        })
                .toList();
    }

    private static Map<String, Object> meta(Map<String, Object> envelope) {
        if (!(envelope.get("meta") instanceof Map<?, ?> metadata))
            throw new IllegalStateException("Statistics provenance is missing");
        return StatsEvidence.copyMap(metadata);
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalStateException("Statistics identity metadata is missing");
        return text;
    }

    private static Double ratio(Object value) {
        if (value == null) return null;
        double number = Double.parseDouble(value.toString());
        if (!Double.isFinite(number) || number < 0 || number > 1)
            throw new IllegalStateException("Invalid statistics ratio");
        return number;
    }
}
