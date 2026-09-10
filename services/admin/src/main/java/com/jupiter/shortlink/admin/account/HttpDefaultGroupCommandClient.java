package com.jupiter.shortlink.admin.account;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Component
public final class HttpDefaultGroupCommandClient implements DefaultGroupCommandClient {
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final URI endpoint;
    private final String token;

    public HttpDefaultGroupCommandClient(
            @Value("${shortlink.command.base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${shortlink.internal-token:}") String token) {
        this.endpoint =
                URI.create(
                        baseUrl.replaceAll("/+$", "") + "/internal/command/accounts/default-group");
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("Invalid Command service URL");
        }
        this.token = token;
    }

    @Override
    public String initialize(AccountInitialization initialization) {
        if (token == null || token.isBlank())
            throw new IllegalStateException("Command service credentials are not configured");
        String payload =
                JSON.toJSONString(
                        Map.of(
                                "tenantId",
                                initialization.tenantId(),
                                "username",
                                initialization.username(),
                                "commandId",
                                initialization.commandId(),
                                "name",
                                "默认分组"));
        HttpRequest request =
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
        try {
            HttpResponse<byte[]> response =
                    http.send(request, ignored -> new BoundedCommandBodySubscriber(8192));
            if (response.statusCode() != 200)
                throw new IllegalStateException("Command initialization was not acknowledged");
            byte[] bytes = response.body();
            JSONObject data =
                    JSON.parseObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            String gid = data == null ? null : data.getString("gid");
            if (gid == null || !gid.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalStateException("Invalid Command initialization result");
            return gid;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command initialization interrupted", interrupted);
        } catch (java.io.IOException transport) {
            throw new IllegalStateException(
                    "Command initialization transport unavailable", transport);
        }
    }
}
