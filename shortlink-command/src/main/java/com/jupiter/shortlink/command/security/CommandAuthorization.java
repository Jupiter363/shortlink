package com.jupiter.shortlink.command.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class CommandAuthorization {
    private final JdbcTemplate jdbc;
    private final byte[] serviceToken;

    public CommandAuthorization(
            JdbcTemplate jdbc, @Value("${shortlink.internal-token}") String token) {
        this.jdbc = jdbc;
        if (token == null || token.length() < 32)
            throw new IllegalArgumentException(
                    "Internal token must contain at least 32 characters");
        serviceToken = token.getBytes(StandardCharsets.UTF_8);
    }

    public void requireService(HttpServletRequest request) {
        String provided = request.getHeader("X-Internal-Token");
        if (provided == null
                || !MessageDigest.isEqual(serviceToken, provided.getBytes(StandardCharsets.UTF_8)))
            denied();
    }

    public CommandPrincipal principal(HttpServletRequest request) {
        requireService(request);
        try {
            CommandPrincipal p =
                    new CommandPrincipal(
                            Long.parseLong(request.getHeader("x-shortlink-tenant-id")),
                            request.getHeader("x-shortlink-username"),
                            Long.parseLong(request.getHeader("x-shortlink-auth-version")));
            check(p, false);
            return p;
        } catch (IllegalArgumentException e) {
            denied();
            return null;
        }
    }

    public void check(CommandPrincipal p, boolean forUpdate) {
        var rows =
                jdbc.queryForList(
                        "SELECT id,auth_version,disabled,del_flag FROM t_user WHERE username=? AND"
                                + " id=?"
                                + (forUpdate ? " FOR UPDATE" : ""),
                        p.username(),
                        p.tenantId());
        if (rows.size() != 1) denied();
        var r = rows.get(0);
        if (((Number) r.get("auth_version")).longValue() != p.authVersion()
                || truthy(r.get("disabled"))
                || ((Number) r.get("del_flag")).intValue() != 0) denied();
    }

    public void checkAccount(long tenantId, String username, boolean forUpdate) {
        var rows =
                jdbc.queryForList(
                        "SELECT id,disabled,del_flag FROM t_user WHERE username=? AND id=?"
                                + (forUpdate ? " FOR UPDATE" : ""),
                        username,
                        tenantId);
        if (rows.size() != 1
                || truthy(rows.get(0).get("disabled"))
                || ((Number) rows.get(0).get("del_flag")).intValue() != 0) denied();
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || (value instanceof Number n && n.intValue() != 0);
    }

    public static void denied() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized");
    }
}
