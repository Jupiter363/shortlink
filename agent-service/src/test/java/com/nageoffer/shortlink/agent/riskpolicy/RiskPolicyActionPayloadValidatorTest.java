package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPayloadV1;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionPayloadValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.stereotype.Component;

import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskPolicyActionPayloadValidatorTest {

    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE = "Risk policy action payload is invalid";
    private static final String IP_HASH = "a".repeat(64);
    private static final LocalDateTime EXPIRE_TIME = LocalDateTime.of(2026, 7, 12, 8, 30);

    private final RiskPolicyActionPayloadValidator validator = new RiskPolicyActionPayloadValidator();

    @Test
    void validatorIsAStatelessComponent() {
        assertThat(RiskPolicyActionPayloadValidator.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(RiskPolicyActionPayloadValidator.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(Modifier.isStatic(field.getModifiers())).isTrue());
    }

    @Test
    void payloadNormalizesAndDefensivelyCopiesAllowedWindows() {
        assertThat(disablePayload(null).allowedWindows()).isEmpty();

        List<String> source = new ArrayList<>(List.of("08:00-12:00"));
        RiskPolicyActionPayloadV1 payload = windowPayload(source);
        source.add("13:00-17:00");

        assertThat(payload.allowedWindows()).containsExactly("08:00-12:00");
        assertThatThrownBy(() -> payload.allowedWindows().add("18:00-19:00"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void payloadRejectsNullWindowElementWithStableActionException() {
        List<String> windows = new ArrayList<>();
        windows.add("08:00-12:00");
        windows.add(null);

        AgentActionException exception = assertThrows(
                AgentActionException.class,
                () -> windowPayload(windows)
        );

        assertStableInvalid(exception);
        assertThat(exception.getCause()).isNull();
    }

    @ParameterizedTest
    @MethodSource("validPayloads")
    void validatesSupportedActionPayloads(RiskPolicyActionPayloadV1 payload) {
        assertThat(validator.validate(payload)).isSameAs(payload);
    }

    @ParameterizedTest
    @MethodSource("missingCommonFields")
    void rejectsMissingCommonFields(RiskPolicyActionPayloadV1 payload) {
        assertInvalid(payload);
    }

    @Test
    void permitsNullableBatchAndExpiryButRejectsUnsupportedLimitRate() {
        RiskPolicyActionPayloadV1 withoutOptionals = new RiskPolicyActionPayloadV1(
                RiskPolicyAction.DISABLE_SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                null,
                null,
                null,
                "Confirmed abusive traffic",
                "event-001",
                null,
                null
        );

        assertThat(validator.validate(withoutOptionals)).isSameAs(withoutOptionals);
        assertInvalid(new RiskPolicyActionPayloadV1(
                RiskPolicyAction.LIMIT_RATE,
                "gid-001",
                "nurl.ink",
                "abc123",
                null,
                null,
                null,
                "Confirmed abusive traffic",
                "event-001",
                null,
                null
        ));
    }

    @ParameterizedTest
    @MethodSource("invalidMutuallyExclusiveFields")
    void rejectsFieldsThatDoNotBelongToTheSelectedAction(RiskPolicyActionPayloadV1 payload) {
        assertInvalid(payload);
    }

    @ParameterizedTest
    @MethodSource("invalidWindows")
    void rejectsMalformedNonForwardDuplicateOrOverlappingWindows(List<String> windows) {
        assertInvalid(windowPayload(windows));
    }

    @Test
    void acceptsAdjacentWindowsInAnyInputOrder() {
        RiskPolicyActionPayloadV1 payload = windowPayload(List.of(
                "12:00-13:00",
                "08:00-12:00",
                "18:30-19:45"
        ));

        assertThat(validator.validate(payload)).isSameAs(payload);
    }

    @ParameterizedTest
    @MethodSource("invalidIpHashes")
    void blockIpRequiresExactlySixtyFourLowercaseHexCharacters(String ipHash) {
        assertInvalid(blockPayload(ipHash));
    }

    @ParameterizedTest
    @MethodSource("payloadsContainingRawIpv4")
    void rejectsObviousRawIpv4InEveryTextSurface(RiskPolicyActionPayloadV1 payload) {
        assertInvalid(payload);
    }

    @ParameterizedTest
    @MethodSource("payloadsContainingRawIpv6")
    void rejectsRawIpv6InEveryTextSurface(RiskPolicyActionPayloadV1 payload) {
        assertInvalid(payload);
    }

    @Test
    void toSafeMapUsesAnImmutableActionSpecificWhitelist() {
        RiskPolicyActionPayloadV1 disable = validator.validate(disablePayload(List.of()));
        RiskPolicyActionPayloadV1 window = validator.validate(windowPayload(List.of("08:00-12:00")));
        RiskPolicyActionPayloadV1 block = validator.validate(blockPayload(IP_HASH));

        Map<String, Object> disableMap = disable.toSafeMap();
        Map<String, Object> windowMap = window.toSafeMap();
        Map<String, Object> blockMap = block.toSafeMap();

        Set<String> commonKeys = Set.of(
                "action", "gid", "domain", "shortUri", "reason", "eventId", "batchId", "expireTime"
        );
        assertThat(disableMap.keySet()).containsExactlyInAnyOrderElementsOf(commonKeys);
        assertThat(windowMap.keySet()).containsExactlyInAnyOrderElementsOf(Stream.concat(
                commonKeys.stream(), Stream.of("timezone", "allowedWindows")
        ).toList());
        assertThat(blockMap.keySet()).containsExactlyInAnyOrderElementsOf(Stream.concat(
                commonKeys.stream(), Stream.of("ipHash")
        ).toList());
        assertThat(disableMap.toString()).doesNotContain("ip=", "rawIp", "ipHash", "timezone", "allowedWindows");
        assertThat(windowMap.toString()).doesNotContain("ip=", "rawIp", "ipHash");
        assertThat(blockMap.toString()).doesNotContain("ip=", "rawIp", "timezone", "allowedWindows");
        assertThat(windowMap.get("allowedWindows")).isEqualTo(List.of("08:00-12:00"));
        assertThatThrownBy(() -> disableMap.put("rawIp", "192.0.2.1"))
                .isInstanceOf(UnsupportedOperationException.class);
        @SuppressWarnings("unchecked")
        List<String> safeWindows = (List<String>) windowMap.get("allowedWindows");
        assertThatThrownBy(() -> safeWindows.add("13:00-14:00"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toSafeMapOmitsNullableCommonFields() {
        RiskPolicyActionPayloadV1 payload = validator.validate(new RiskPolicyActionPayloadV1(
                RiskPolicyAction.DISABLE_SHORT_LINK,
                "gid-001",
                "nurl.ink",
                "abc123",
                null,
                null,
                List.of(),
                "Confirmed abusive traffic",
                "event-001",
                null,
                null
        ));

        assertThat(payload.toSafeMap())
                .doesNotContainKeys("batchId", "expireTime", "ipHash", "timezone", "allowedWindows");
    }

    private static Stream<RiskPolicyActionPayloadV1> validPayloads() {
        return Stream.of(
                disablePayload(List.of()),
                windowPayload(List.of("08:00-12:00", "13:00-17:00")),
                blockPayload(IP_HASH)
        );
    }

    private static Stream<RiskPolicyActionPayloadV1> missingCommonFields() {
        return Stream.of(
                commonPayload(null, "gid-001", "nurl.ink", "abc123", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, null, "nurl.ink", "abc123", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, " ", "nurl.ink", "abc123", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", null, "abc123", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "abc123", null, "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "abc123", "reason", " ")
        );
    }

    private static Stream<RiskPolicyActionPayloadV1> invalidMutuallyExclusiveFields() {
        return Stream.of(
                payload(RiskPolicyAction.DISABLE_SHORT_LINK, IP_HASH, null, List.of()),
                payload(RiskPolicyAction.DISABLE_SHORT_LINK, null, "Asia/Shanghai", List.of()),
                payload(RiskPolicyAction.DISABLE_SHORT_LINK, null, null, List.of("08:00-12:00")),
                payload(RiskPolicyAction.LIMIT_TIME_WINDOW, IP_HASH, "Asia/Shanghai", List.of("08:00-12:00")),
                payload(RiskPolicyAction.LIMIT_TIME_WINDOW, null, null, List.of("08:00-12:00")),
                payload(RiskPolicyAction.LIMIT_TIME_WINDOW, null, "Not/AZone", List.of("08:00-12:00")),
                payload(RiskPolicyAction.LIMIT_TIME_WINDOW, null, "Asia/Shanghai", List.of()),
                payload(RiskPolicyAction.BLOCK_IP, IP_HASH, "Asia/Shanghai", List.of()),
                payload(RiskPolicyAction.BLOCK_IP, IP_HASH, null, List.of("08:00-12:00"))
        );
    }

    private static Stream<List<String>> invalidWindows() {
        return Stream.of(
                List.of("8:00-12:00"),
                List.of("08:00 -12:00"),
                List.of("08:00-12:00 "),
                List.of("24:00-24:01"),
                List.of("08:60-09:00"),
                List.of("12:00-12:00"),
                List.of("13:00-12:00"),
                List.of("08:00-12:00", "08:00-12:00"),
                List.of("08:00-12:00", "11:59-13:00"),
                List.of("11:59-13:00", "08:00-12:00")
        );
    }

    private static Stream<String> invalidIpHashes() {
        return Stream.of(null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64));
    }

    private static Stream<RiskPolicyActionPayloadV1> payloadsContainingRawIpv4() {
        String rawIp = "192.0.2.44";
        return Stream.of(
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-" + rawIp, "nurl.ink", "abc123", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", rawIp, "abc123", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "path-" + rawIp, "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "abc123", "source " + rawIp, "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "abc123", "reason", "event-" + rawIp),
                new RiskPolicyActionPayloadV1(
                        RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "abc123", null, null, List.of(),
                        "reason", "event-001", "batch-" + rawIp, EXPIRE_TIME
                ),
                payload(RiskPolicyAction.LIMIT_TIME_WINDOW, null, "Asia/" + rawIp, List.of("08:00-12:00"))
        );
    }

    private static Stream<RiskPolicyActionPayloadV1> payloadsContainingRawIpv6() {
        String rawIp = "2001:db8::44";
        return Stream.of(
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-" + rawIp, "nurl.ink", "abc123", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", rawIp, "abc123", "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "path-" + rawIp, "reason", "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "abc123", "source " + rawIp, "event-001"),
                commonPayload(RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "abc123", "reason", "event-" + rawIp),
                new RiskPolicyActionPayloadV1(
                        RiskPolicyAction.DISABLE_SHORT_LINK, "gid-001", "nurl.ink", "abc123", null, null, List.of(),
                        "reason", "event-001", "batch-" + rawIp, EXPIRE_TIME
                ),
                payload(RiskPolicyAction.LIMIT_TIME_WINDOW, null, "Asia/" + rawIp, List.of("08:00-12:00"))
        );
    }

    private static RiskPolicyActionPayloadV1 disablePayload(List<String> windows) {
        return payload(RiskPolicyAction.DISABLE_SHORT_LINK, null, null, windows);
    }

    private static RiskPolicyActionPayloadV1 windowPayload(List<String> windows) {
        return payload(RiskPolicyAction.LIMIT_TIME_WINDOW, null, "Asia/Shanghai", windows);
    }

    private static RiskPolicyActionPayloadV1 blockPayload(String ipHash) {
        return payload(RiskPolicyAction.BLOCK_IP, ipHash, null, List.of());
    }

    private static RiskPolicyActionPayloadV1 payload(
            RiskPolicyAction action,
            String ipHash,
            String timezone,
            List<String> windows
    ) {
        return new RiskPolicyActionPayloadV1(
                action,
                "gid-001",
                "nurl.ink",
                "abc123",
                ipHash,
                timezone,
                windows,
                "Confirmed abusive traffic",
                "event-001",
                "batch-001",
                EXPIRE_TIME
        );
    }

    private static RiskPolicyActionPayloadV1 commonPayload(
            RiskPolicyAction action,
            String gid,
            String domain,
            String shortUri,
            String reason,
            String eventId
    ) {
        return new RiskPolicyActionPayloadV1(
                action, gid, domain, shortUri, null, null, List.of(), reason, eventId, "batch-001", EXPIRE_TIME
        );
    }

    private void assertInvalid(RiskPolicyActionPayloadV1 payload) {
        AgentActionException exception = assertThrows(AgentActionException.class, () -> validator.validate(payload));
        assertStableInvalid(exception);
        assertThat(exception.getCause()).isNull();
    }

    private static void assertStableInvalid(AgentActionException exception) {
        assertThat(exception.code()).isEqualTo(INVALID_CODE);
        assertThat(exception.getMessage()).isEqualTo(INVALID_MESSAGE);
    }
}
