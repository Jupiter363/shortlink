package com.nageoffer.shortlink.admin.authority.identity.api;

import com.nageoffer.shortlink.admin.authority.identity.model.TokenExchangeResponse;
import com.nageoffer.shortlink.admin.authority.identity.service.AgentTokenExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AgentTokenExchangeController {

    private final AgentTokenExchangeService tokenExchangeService;

    @PostMapping(
            value = "/internal/short-link-admin/v1/agent-identity/token/exchange",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<TokenExchangeResponse> exchange(
            HttpServletRequest request,
            @RequestParam("grant_type") String grantType,
            @RequestParam("subject_token") String subjectToken,
            @RequestParam("subject_token_type") String subjectTokenType,
            @RequestParam("audience") String audience,
            @RequestParam("scope") String scope
    ) {
        TokenExchangeResponse response = tokenExchangeService.exchange(
                request,
                grantType,
                subjectToken,
                subjectTokenType,
                audience,
                scope
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }
}
