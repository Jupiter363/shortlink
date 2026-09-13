package com.jupiter.shortlink.agent.infrastructure.persistence;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.JacksonDeserializer;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.TypeMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Keeps the Alibaba wire format while preserving declared List element types on restore. */
public final class AgentStateSerializerFactory {
    private AgentStateSerializerFactory() {}

    public static SpringAIJacksonStateSerializer create() {
        var serializer = new SpringAIJacksonStateSerializer(OverAllState::new);
        // Alibaba 1.1.2.3's global GenericListDeserializer discards List<T>. In particular,
        // List<RiskReasonCode> becomes List<String> after graph cloning, then fails on save.
        var module = new SimpleModule("shortlink-contextual-state-lists");
        module.addDeserializer(List.class, new TypedListDeserializer(serializer.typeMapper(), null));
        serializer.objectMapper().registerModule(module);
        return serializer;
    }

    private static final class TypedListDeserializer extends StdDeserializer<List>
            implements ContextualDeserializer {
        private final TypeMapper typeMapper;
        private final JavaType elementType;

        private TypedListDeserializer(TypeMapper typeMapper, JavaType elementType) {
            super(List.class);
            this.typeMapper = typeMapper;
            this.elementType = elementType;
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property)
                throws JsonMappingException {
            JavaType type = context.getContextualType();
            if (type == null && property != null) type = property.getType();
            return new TypedListDeserializer(typeMapper,
                    type != null && type.isCollectionLikeType() ? type.getContentType() : null);
        }

        @Override
        public List deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            ObjectMapper mapper = (ObjectMapper) parser.getCodec();
            JsonNode node = mapper.readTree(parser);
            if (node == null || node.isNull()) return new ArrayList<>();
            if (!node.isArray())
                throw JsonMappingException.from(parser, "Expected an array for a graph state list");
            List<Object> result = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                if (elementType == null || elementType.hasRawClass(Object.class)) {
                    // Retain the original type mapper and its metadata handling for heterogeneous
                    // graph state/message lists. Do not add polymorphic typing or class resolution.
                    result.add(JacksonDeserializer.valueFromNode(item, mapper, typeMapper));
                } else {
                    try (JsonParser element = item.traverse(mapper)) {
                        element.nextToken();
                        result.add(context.readValue(element, elementType));
                    }
                }
            }
            return result;
        }
    }
}
