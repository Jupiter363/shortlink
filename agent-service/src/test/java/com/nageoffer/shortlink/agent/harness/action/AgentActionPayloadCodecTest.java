package com.nageoffer.shortlink.agent.harness.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.harness.action.service.AgentActionPayloadCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActionPayloadCodecTest {

    @Test
    void canonicalJsonAndHashIgnoreMapInsertionOrder() {
        AgentActionPayloadCodec codec = new AgentActionPayloadCodec(new ObjectMapper());
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("gid", "g1");
        first.put("domain", "nurl.ink");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("domain", "nurl.ink");
        second.put("gid", "g1");

        assertThat(codec.canonicalJson(first))
                .isEqualTo("{\"domain\":\"nurl.ink\",\"gid\":\"g1\"}")
                .isEqualTo(codec.canonicalJson(second));
        assertThat(codec.hash(first)).isEqualTo(codec.hash(second));
    }

    @Test
    void canonicalJsonSortsNestedMapsAndPojoPropertiesButPreservesListOrder() {
        AgentActionPayloadCodec codec = new AgentActionPayloadCodec(new ObjectMapper());
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 2);
        nested.put("a", 1);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pojo", new UnorderedPojo("z", "a"));
        payload.put("nested", nested);
        payload.put("steps", List.of("first", "second"));

        assertThat(codec.canonicalJson(payload)).isEqualTo(
                "{\"nested\":{\"a\":1,\"z\":2},"
                        + "\"pojo\":{\"alpha\":\"a\",\"zeta\":\"z\"},"
                        + "\"steps\":[\"first\",\"second\"]}"
        );

        Map<String, Object> reversed = new LinkedHashMap<>(payload);
        reversed.put("steps", List.of("second", "first"));
        assertThat(codec.canonicalJson(reversed)).isNotEqualTo(codec.canonicalJson(payload));
        assertThat(codec.hash(reversed)).isNotEqualTo(codec.hash(payload));
    }

    @Test
    void hashIsAlwaysLowercaseSha256Hex() {
        AgentActionPayloadCodec codec = new AgentActionPayloadCodec(new ObjectMapper());

        assertThat(codec.hash(Map.of("action", "risk.disable-short-link")))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void codecCopiesMapperWithoutChangingExternalConfiguration() {
        ObjectMapper external = new ObjectMapper()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, false)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);

        new AgentActionPayloadCodec(external);

        assertThat(external.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)).isFalse();
        assertThat(external.isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)).isFalse();
    }

    @Test
    void serializationFailureUsesStableCodeAndDoesNotLeakPayloadText() {
        AgentActionPayloadCodec codec = new AgentActionPayloadCodec(new ObjectMapper());
        UnserializablePayload payload = new UnserializablePayload("payload-secret-marker");

        Throwable thrown = catchThrowable(() -> codec.canonicalJson(payload));

        assertThat(thrown)
                .isInstanceOf(AgentActionException.class)
                .hasMessage("Agent action payload cannot be serialized")
                .hasCauseInstanceOf(JsonProcessingException.class);
        AgentActionException exception = (AgentActionException) thrown;
        assertThat(exception.code()).isEqualTo("ACTION_PAYLOAD_INVALID");
        assertThat(exception.getMessage()).doesNotContain("payload-secret-marker");

        Throwable hashFailure = catchThrowable(() -> codec.hash(payload));
        assertThat(hashFailure)
                .isInstanceOf(AgentActionException.class)
                .hasMessage("Agent action payload cannot be serialized");
        assertThat(((AgentActionException) hashFailure).code()).isEqualTo("ACTION_PAYLOAD_INVALID");
    }

    @Test
    void readMapRejectsMissingMalformedAndNonObjectJsonWithoutLeakingInput() {
        AgentActionPayloadCodec codec = new AgentActionPayloadCodec(new ObjectMapper());

        for (String json : new String[]{
                null,
                " ",
                "{\"token\":\"payload-secret-marker\"",
                "[\"payload-secret-marker\"]"
        }) {
            Throwable thrown = catchThrowable(() -> codec.readMap(json));
            assertThat(thrown)
                    .isInstanceOf(AgentActionException.class)
                    .hasMessage("Agent action payload is invalid")
                    .hasNoCause();
            assertThat(((AgentActionException) thrown).code()).isEqualTo("ACTION_PAYLOAD_INVALID");
            assertThat(thrown.toString()).doesNotContain("payload-secret-marker", "token");
        }

        Map<String, Object> parsed = codec.readMap("{\"nested\":[{\"ok\":true}]}");
        assertThat(parsed).containsKey("nested");
        assertThatThrownBy(() -> parsed.put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void readMapIgnoresDefaultTypingAndNeverInstantiatesTypeIds() {
        DefaultTypingProbe.constructorCalls.set(0);
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        AgentActionPayloadCodec codec = new AgentActionPayloadCodec(mapper);
        String typeName = DefaultTypingProbe.class.getName();
        String json = "{\"@class\":\"" + typeName + "\","
                + "\"value\":\"plain\","
                + "\"items\":[{\"@class\":\"" + typeName + "\"}]}";

        Map<String, Object> parsed = codec.readMap(json);

        assertThat(DefaultTypingProbe.constructorCalls).hasValue(0);
        assertThat(parsed)
                .containsEntry("@class", typeName)
                .containsEntry("value", "plain");
        assertThat(parsed.get("items")).isInstanceOf(List.class);
        assertThat(((List<?>) parsed.get("items")).get(0)).isInstanceOf(Map.class);
        assertThatThrownBy(() -> parsed.put("new", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) parsed.get("items")).add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static final class UnorderedPojo {

        private final String zeta;
        private final String alpha;

        private UnorderedPojo(String zeta, String alpha) {
            this.zeta = zeta;
            this.alpha = alpha;
        }

        public String getZeta() {
            return zeta;
        }

        public String getAlpha() {
            return alpha;
        }
    }

    private static final class UnserializablePayload {

        private final String marker;

        private UnserializablePayload(String marker) {
            this.marker = marker;
        }

        public UnserializablePayload getSelf() {
            return this;
        }

        @Override
        public String toString() {
            return marker;
        }
    }

    public static final class DefaultTypingProbe {

        private static final AtomicInteger constructorCalls = new AtomicInteger();

        public DefaultTypingProbe() {
            constructorCalls.incrementAndGet();
            throw new IllegalStateException("Default typing must never instantiate this type");
        }
    }
}
