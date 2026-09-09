package com.jupiter.shortlink.admin.controller;

import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.convention.result.Result;
import com.jupiter.shortlink.admin.common.convention.result.Results;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.*;
import java.nio.charset.StandardCharsets;

@RestController(value = "urlTitleControllerByAdmin")
public class UrlTitleController {

    private final String baseUrl, token;

    public UrlTitleController(
            @Value("${shortlink.command.base-url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${shortlink.internal-token}") String token) {
        if (token.length() < 32)
            throw new IllegalArgumentException("Internal service token is required");
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    /** 根据url获取对应网站的标题 */
    @GetMapping("/api/short-link/admin/v1/tittle")
    public Result<String> getTitleByUrl(@RequestParam("url") String url) {
        String tenant = UserContext.getUserId(), username = UserContext.getUsername();
        Long version = UserContext.getAuthVersion();
        if (tenant == null || username == null || version == null)
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Management session required");
        if (url == null || url.length() > 2048) throw new IllegalArgumentException("Invalid URL");
        HttpURLConnection connection = null;
        try {
            connection =
                    (HttpURLConnection)
                            URI.create(
                                            baseUrl
                                                    + "/internal/command/metadata/title?url="
                                                    + URLEncoder.encode(
                                                            url, StandardCharsets.UTF_8))
                                    .toURL()
                                    .openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(6000);
            connection.setRequestProperty("X-Internal-Token", token);
            connection.setRequestProperty("x-shortlink-tenant-id", tenant);
            connection.setRequestProperty("x-shortlink-username", username);
            connection.setRequestProperty("x-shortlink-auth-version", Long.toString(version));
            if (connection.getResponseCode() != 200)
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Metadata service rejected the fetch");
            try (var input = connection.getInputStream()) {
                byte[] result = input.readNBytes(4097);
                if (result.length > 4096)
                    throw new IllegalStateException("Metadata response exceeded budget");
                return Results.success(new String(result, StandardCharsets.UTF_8));
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Metadata service unavailable", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
