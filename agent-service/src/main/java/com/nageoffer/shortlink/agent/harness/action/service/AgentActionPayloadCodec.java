package com.nageoffer.shortlink.agent.harness.action.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public class AgentActionPayloadCodec {

    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE = "Agent action payload cannot be serialized";

    private final ObjectMapper objectMapper;

    public AgentActionPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
                .copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException | RuntimeException ex) {
            throw new AgentActionException(INVALID_CODE, INVALID_MESSAGE, ex);
        }
    }

    public String hash(Object value) {
        byte[] canonicalBytes = canonicalJson(value).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
