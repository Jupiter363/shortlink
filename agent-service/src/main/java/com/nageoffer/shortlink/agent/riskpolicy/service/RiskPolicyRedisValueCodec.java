package com.nageoffer.shortlink.agent.riskpolicy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

@Component
public class RiskPolicyRedisValueCodec {

    private static final TypeReference<LinkedHashMap<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public RiskPolicyRedisValueCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(String policyId, long policyVersion, String policyPayloadJson) {
        if (!StringUtils.hasText(policyId)) {
            throw new IllegalArgumentException("policyId must not be blank");
        }
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("policyVersion must be positive");
        }

        Map<String, Object> payload = readPayload(policyPayloadJson);
        payload.put("policyId", policyId);
        payload.put("policyVersion", policyVersion);
        return writeStable(payload, "Failed to encode risk policy Redis value");
    }

    public String encodePayload(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Risk policy payload must not be null");
        }
        return writeStable(payload, "Failed to encode risk policy payload");
    }

    private String writeStable(Map<String, Object> payload, String failureMessage) {
        try {
            return objectMapper.writeValueAsString(new TreeMap<>(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException(failureMessage, ex);
        }
    }

    private Map<String, Object> readPayload(String policyPayloadJson) {
        if (!StringUtils.hasText(policyPayloadJson)) {
            throw new IllegalArgumentException("Risk policy payload must be a JSON object");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(policyPayloadJson, PAYLOAD_TYPE);
            if (payload == null) {
                throw new IllegalArgumentException("Risk policy payload must be a JSON object");
            }
            return payload;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Risk policy payload must be a JSON object", ex);
        }
    }
}
