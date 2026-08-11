package com.nageoffer.shortlink.admin.authority.identity.api;

import com.nageoffer.shortlink.admin.authority.identity.crypto.AgentIdentityKeyRing;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AgentJwksController {

    private final AgentIdentityKeyRing keyRing;

    @GetMapping("/internal/short-link-admin/v1/agent-identity/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keyRing.publicJwkSet().toJSONObject(false));
    }
}
