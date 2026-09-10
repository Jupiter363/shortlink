package com.jupiter.shortlink.agent.business.shortlink;

import com.jupiter.shortlink.agent.harness.tool.ToolContext;
import com.jupiter.shortlink.agent.harness.tool.ToolResult;
import com.jupiter.shortlink.agent.infrastructure.config.AgentProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class ShortLinkBusinessHttpGateway implements ShortLinkBusinessGateway {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token";

    private static final String USERNAME_HEADER = "X-Agent-Username";

    private final AgentProperties agentProperties;

    private final RestTemplate restTemplate;
    private com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport transport;

    @Autowired
    public ShortLinkBusinessHttpGateway(
            AgentProperties agentProperties,
            com.jupiter.shortlink.agent.infrastructure.llm.BoundedHttpTransport transport) {
        this(agentProperties, (RestTemplate) null);
        this.transport = transport;
    }

    ShortLinkBusinessHttpGateway(AgentProperties agentProperties, RestTemplate restTemplate) {
        this.agentProperties = agentProperties;
        this.restTemplate = restTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult get(String path, ToolContext context, Map<String, Object> queryParams) {
        try {
            Map<String, Object> body;
            if (transport != null)
                body =
                        transport.exchange(
                                "GET",
                                uri(path, queryParams),
                                headers(context).toSingleValueMap(),
                                null);
            else {
                ResponseEntity<Map> response =
                        restTemplate.exchange(
                                uri(path, queryParams),
                                HttpMethod.GET,
                                new HttpEntity<>(headers(context)),
                                Map.class);
                body = response.getBody();
            }
            if (body == null) {
                return ToolResult.failure("Short link business API returned empty response");
            }
            if (!isSuccess(body)) {
                return ToolResult.failure(message(body));
            }
            return ToolResult.success(body.get("data"));
        } catch (RuntimeException ex) {
            return ToolResult.failure("Short link business API is unavailable or unauthorized");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult post(String path, ToolContext context, Map<String, Object> payload) {
        try {
            Map<String, Object> body =
                    transport != null
                            ? transport.exchange(
                                    "POST",
                                    uri(path, Map.of()),
                                    headers(context).toSingleValueMap(),
                                    payload)
                            : restTemplate
                                    .exchange(
                                            uri(path, Map.of()),
                                            HttpMethod.POST,
                                            new HttpEntity<>(payload, headers(context)),
                                            Map.class)
                                    .getBody();
            if (body == null)
                return ToolResult.failure("Statistics job API returned an empty response");
            return isSuccess(body)
                    ? ToolResult.success(body.get("data"))
                    : ToolResult.failure(message(body));
        } catch (RuntimeException failure) {
            return ToolResult.failure("Statistics job API is unavailable or unauthorized");
        }
    }

    private URI uri(String path, Map<String, Object> queryParams) {
        UriComponentsBuilder builder =
                UriComponentsBuilder.fromHttpUrl(baseUrl() + normalizePath(path));
        safeQueryParams(queryParams)
                .forEach(
                        (key, value) -> {
                            if (value != null) {
                                builder.queryParam(key, value);
                            }
                        });
        return builder.build().encode().toUri();
    }

    private HttpHeaders headers(ToolContext context) {
        HttpHeaders headers = new HttpHeaders();
        if (context == null
                || context.principal() == null
                || !context.username().equals(context.principal().username())) {
            throw new IllegalArgumentException("Trusted Agent principal is required");
        }
        headers.add(USERNAME_HEADER, context.principal().username());
        if (context.principal().system()) headers.add("X-Agent-Principal-Mode", "SYSTEM");
        else {
            headers.add("X-Agent-UserId", context.principal().tenantId());
            headers.add("X-Agent-Auth-Version", Long.toString(context.principal().authVersion()));
        }
        String internalToken = agentProperties.getBusiness().getInternalToken();
        if (internalToken == null || internalToken.length() < 24)
            throw new SecurityException("Service credential is unavailable");
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

    private Map<String, Object> safeQueryParams(Map<String, Object> queryParams) {
        return queryParams == null ? Map.of() : queryParams;
    }

    private boolean isSuccess(Map<String, Object> body) {
        Object success = body.get("success");
        Object code = body.get("code");
        return Boolean.TRUE.equals(success) || "0".equals(String.valueOf(code));
    }

    private String message(Map<String, Object> body) {
        Object message = body.get("message");
        Object code = body.get("code");
        if (message != null) {
            return String.valueOf(message);
        }
        return "Short link business API failed with code " + code;
    }
}
