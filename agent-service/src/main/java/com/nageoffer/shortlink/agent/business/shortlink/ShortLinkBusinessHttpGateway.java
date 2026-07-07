package com.nageoffer.shortlink.agent.business.shortlink;

import com.nageoffer.shortlink.agent.infrastructure.config.AgentProperties;
import com.nageoffer.shortlink.agent.tool.core.ToolContext;
import com.nageoffer.shortlink.agent.tool.core.ToolResult;
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
import java.util.Map;

@Component
public class ShortLinkBusinessHttpGateway implements ShortLinkBusinessGateway {

    private static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token";

    private static final String USERNAME_HEADER = "X-Agent-Username";

    private final AgentProperties agentProperties;

    private final RestTemplate restTemplate;

    @Autowired
    public ShortLinkBusinessHttpGateway(AgentProperties agentProperties, RestTemplateBuilder restTemplateBuilder) {
        this(agentProperties, restTemplateBuilder.build());
    }

    ShortLinkBusinessHttpGateway(AgentProperties agentProperties, RestTemplate restTemplate) {
        this.agentProperties = agentProperties;
        this.restTemplate = restTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult get(String path, ToolContext context, Map<String, Object> queryParams) {
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    uri(path, queryParams),
                    HttpMethod.GET,
                    new HttpEntity<>(headers(context)),
                    Map.class
            );
            Map<String, Object> body = response.getBody();
            if (body == null) {
                return ToolResult.failure("Short link business API returned empty response");
            }
            if (!isSuccess(body)) {
                return ToolResult.failure(message(body));
            }
            return ToolResult.success(body.get("data"));
        } catch (RestClientException ex) {
            return ToolResult.failure("Short link business API request failed: " + ex.getMessage());
        }
    }

    private URI uri(String path, Map<String, Object> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl() + normalizePath(path));
        safeQueryParams(queryParams).forEach((key, value) -> {
            if (value != null) {
                builder.queryParam(key, value);
            }
        });
        return builder.build(true).toUri();
    }

    private HttpHeaders headers(ToolContext context) {
        HttpHeaders headers = new HttpHeaders();
        if (context != null && StringUtils.hasText(context.username())) {
            headers.add(USERNAME_HEADER, context.username());
        }
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
