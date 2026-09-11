package com.jupiter.shortlink.admin.common.biz.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jupiter.shortlink.admin.common.biz.user.UserContext;
import com.jupiter.shortlink.admin.common.biz.user.UserInfoDTO;
import com.jupiter.shortlink.admin.config.AgentAdminConfiguration;
import com.jupiter.shortlink.admin.dao.entity.UserDO;
import com.jupiter.shortlink.admin.dao.mapper.UserMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Set;

public class AgentInternalToolApiFilter extends OncePerRequestFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Agent-Internal-Token";

    public static final String USERNAME_HEADER = "X-Agent-Username";

    public static final String USER_ID_HEADER = "X-Agent-UserId";

    public static final String REAL_NAME_HEADER = "X-Agent-RealName";
    public static final String AUTH_VERSION_HEADER = "X-Agent-Auth-Version";
    public static final String PRINCIPAL_MODE_HEADER = "X-Agent-Principal-Mode";

    private static final String INTERNAL_TOOL_API_PREFIX =
            "/internal/short-link-admin/v1/agent-tools/";
    private static final String VERIFIED = AgentInternalToolApiFilter.class.getName() + ".verified";
    private static final Set<String> SINGLE_HEADERS = Set.of(INTERNAL_TOKEN_HEADER, USERNAME_HEADER,
            USER_ID_HEADER, REAL_NAME_HEADER, AUTH_VERSION_HEADER, PRINCIPAL_MODE_HEADER);

    private final AgentAdminConfiguration agentAdminConfiguration;
    private final UserMapper userMapper;
    private final String systemUsername;

    public AgentInternalToolApiFilter(AgentAdminConfiguration agentAdminConfiguration) {
        this(agentAdminConfiguration, null, "");
    }

    public AgentInternalToolApiFilter(
            AgentAdminConfiguration agentAdminConfiguration,
            UserMapper userMapper,
            String systemUsername) {
        this.agentAdminConfiguration = agentAdminConfiguration;
        this.userMapper = userMapper;
        this.systemUsername = systemUsername == null ? "" : systemUsername;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isInternalToolApi(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            Object saved = request.getAttribute(VERIFIED);
            if (!(saved instanceof Verified verified) || !verified.path().equals(request.getRequestURI())
                    || !verified.method().equals(request.getMethod())) {
                writeError(response, HttpStatus.UNAUTHORIZED, "Missing authenticated async principal");
                return;
            }
            UserContext.setUser(verified.principal());
            try { filterChain.doFilter(request, response); }
            finally { UserContext.removeUser(); }
            return;
        }
        for (String header : SINGLE_HEADERS) {
            if (Collections.list(request.getHeaders(header)).size() > 1) {
                writeError(response, HttpStatus.BAD_REQUEST, "Duplicate internal authentication header");
                return;
            }
        }
        String internalToken = agentAdminConfiguration.getInternalToken();
        if (!StringUtils.hasText(internalToken) || internalToken.length() < 24) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Internal token is not configured");
            return;
        }
        String supplied = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (supplied == null
                || !MessageDigest.isEqual(
                        internalToken.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid internal token");
            return;
        }
        String username = request.getHeader(USERNAME_HEADER);
        if (username == null || !username.matches("[A-Za-z0-9_-]{3,64}")) {
            writeError(response, HttpStatus.BAD_REQUEST, "Missing trusted agent username");
            return;
        }
        if (userMapper == null) {
            writeError(
                    response, HttpStatus.SERVICE_UNAVAILABLE, "Account authority is unavailable");
            return;
        }
        UserDO account;
        try {
            account =
                    userMapper.selectOne(
                            Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUsername, username));
        } catch (RuntimeException unavailable) {
            writeError(
                    response, HttpStatus.SERVICE_UNAVAILABLE, "Account authority is unavailable");
            return;
        }
        if (account == null
                || !Integer.valueOf(0).equals(account.getDelFlag())
                || !Boolean.FALSE.equals(account.getDisabled())
                || account.getId() == null
                || account.getAuthVersion() == null
                || account.getAuthVersion() < 1) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Account is unavailable");
            return;
        }
        boolean system = "SYSTEM".equals(request.getHeader(PRINCIPAL_MODE_HEADER));
        if (system) {
            if (systemUsername.isBlank() || !systemUsername.equals(username)) {
                writeError(
                        response,
                        HttpStatus.FORBIDDEN,
                        "System principal is outside configured scope");
                return;
            }
        } else if (!account.getId().toString().equals(request.getHeader(USER_ID_HEADER))
                || !account.getAuthVersion()
                        .toString()
                        .equals(request.getHeader(AUTH_VERSION_HEADER))) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Delegated account session has expired");
            return;
        }
        UserInfoDTO principal = new UserInfoDTO(
                        account.getId().toString(),
                        username,
                        account.getRealName(),
                        account.getAuthVersion());
        request.setAttribute(VERIFIED, new Verified(request.getRequestURI(), request.getMethod(), principal));
        UserContext.setUser(principal);
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.removeUser();
        }
    }

    private record Verified(String path, String method, UserInfoDTO principal) {}

    private boolean isInternalToolApi(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }
        return requestPath.startsWith(INTERNAL_TOOL_API_PREFIX);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter()
                .write(
                        "{\"success\":false,\"code\":\"%s\",\"message\":\"%s\"}"
                                .formatted(status.value(), message));
    }
}
