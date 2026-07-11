package com.nageoffer.shortlink.agent.harness.action.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class AgentActionPayloadCodec {

    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE = "Agent action payload cannot be serialized";
    private static final String INVALID_READ_MESSAGE = "Agent action payload is invalid";

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

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            throw invalidRead();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw invalidRead();
            }
            return readObject(root);
        } catch (AgentActionException ex) {
            throw ex;
        } catch (JsonProcessingException | RuntimeException ignored) {
            throw invalidRead();
        }
    }

    private Map<String, Object> readObject(JsonNode objectNode) {
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            values.put(field.getKey(), readNode(field.getValue()));
        }
        return values.isEmpty() ? Map.of() : Collections.unmodifiableMap(values);
    }

    private Object readNode(JsonNode node) {
        if (node.isObject()) {
            return readObject(node);
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            node.forEach(item -> values.add(readNode(item)));
            return values.isEmpty() ? List.of() : Collections.unmodifiableList(values);
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber() || node.isFloatingPointNumber()) {
            return node.numberValue();
        }
        throw invalidRead();
    }

    private AgentActionException invalidRead() {
        return new AgentActionException(INVALID_CODE, INVALID_READ_MESSAGE);
    }

}
