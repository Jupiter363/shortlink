package com.nageoffer.shortlink.agent.riskpolicy;

import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskManualActionDirective;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskManualActionDirectiveParser;
import com.nageoffer.shortlink.agent.riskpolicy.action.RiskPolicyActionTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskManualActionDirectiveParserTest {

    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE = "Risk manual action directive payload is invalid";

    private final RiskManualActionDirectiveParser parser = new RiskManualActionDirectiveParser();

    @Test
    void exposesNamespacedManualRiskActionTypes() {
        assertThat(RiskPolicyActionTypes.DISABLE_SHORT_LINK.value())
                .isEqualTo("risk.disable-short-link");
        assertThat(RiskPolicyActionTypes.LIMIT_TIME_WINDOW.value())
                .isEqualTo("risk.limit-time-window");
        assertThat(RiskPolicyActionTypes.BLOCK_IP.value())
                .isEqualTo("risk.block-ip");
        assertThat(Modifier.isFinal(RiskPolicyActionTypes.class.getModifiers())).isTrue();
        assertThat(RiskPolicyActionTypes.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
    }

    @Test
    void parsesAndCanonicalizesLimitTimeWindowDirective() {
        Optional<RiskManualActionDirective> directive = parser.parse(
                "action=LIMIT_TIME_WINDOW timezone=Asia/Shanghai "
                        + "allowedWindows=19:00-21:00|09:00-18:00"
        );

        assertThat(directive).isPresent().get().satisfies(value -> {
            assertThat(value.action()).isEqualTo(RiskPolicyAction.LIMIT_TIME_WINDOW);
            assertThat(value.timezone()).isEqualTo("Asia/Shanghai");
            assertThat(value.allowedWindows())
                    .containsExactly("09:00-18:00", "19:00-21:00");
        });
    }

    @Test
    void parsesDisableShortLinkDirectiveWithoutUnrelatedPayload() {
        assertThat(parser.parse("action=DISABLE_SHORT_LINK"))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.action()).isEqualTo(RiskPolicyAction.DISABLE_SHORT_LINK);
                    assertThat(value.timezone()).isNull();
                    assertThat(value.allowedWindows()).isEmpty();
                });
    }

    @Test
    void parsesBlockIpOnlyFromExplicitActionWithoutIdentityPayload() {
        assertThat(parser.parse("action=BLOCK_IP"))
                .isPresent()
                .get()
                .satisfies(value -> {
                    assertThat(value.action()).isEqualTo(RiskPolicyAction.BLOCK_IP);
                    assertThat(value.timezone()).isNull();
                    assertThat(value.allowedWindows()).isEmpty();
                });
        assertThat(parser.parse("timezone=UTC allowedWindows=09:00-10:00")).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   \t  ",
            "timezone=UTC",
            "action=UNKNOWN",
            "action=LIMIT_RATE",
            "action=disable_short_link"
    })
    void returnsEmptyWhenNoSupportedManualActionIsExplicit(String message) {
        assertThat(parser.parse(message)).isEmpty();
    }

    @Test
    void unknownActionRemainsEmptyWithoutValidatingItsPayload() {
        assertThat(parser.parse("action=UNKNOWN timezone=Not/AZone allowedWindows=invalid"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "action=LIMIT_TIME_WINDOW allowedWindows=09:00-18:00",
            "action=LIMIT_TIME_WINDOW timezone=Asia/Shanghai",
            "action=LIMIT_TIME_WINDOW timezone=Not/AZone allowedWindows=09:00-18:00"
    })
    void rejectsMissingOrInvalidLimitTimeWindowTimezoneAndWindows(String message) {
        assertInvalid(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=09:00-",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=9:00-10:00",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=09:60-10:00",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=18:00-09:00",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=09:00-09:00",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=09:00-10:00||11:00-12:00",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=09:00-10:00|",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=09:00-11:00|09:00-11:00",
            "action=LIMIT_TIME_WINDOW timezone=UTC allowedWindows=09:00-12:00|11:00-13:00"
    })
    void rejectsMalformedReversedDuplicateOrOverlappingWindows(String message) {
        assertInvalid(message);
    }

    @Test
    void acceptsAdjacentWindowsAndReturnsCanonicalOrder() {
        assertThat(parser.parse(
                "action=LIMIT_TIME_WINDOW timezone=UTC "
                        + "allowedWindows=12:00-18:00|09:00-12:00"
        )).isPresent().get().satisfies(value -> assertThat(value.allowedWindows())
                .containsExactly("09:00-12:00", "12:00-18:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "action=DISABLE_SHORT_LINK timezone=UTC",
            "action=DISABLE_SHORT_LINK allowedWindows=09:00-10:00",
            "action=BLOCK_IP timezone=UTC",
            "action=BLOCK_IP allowedWindows=09:00-10:00"
    })
    void rejectsTimeWindowPayloadForActionsThatDoNotUseIt(String message) {
        assertInvalid(message);
    }

    @Test
    void rejectsRawIpWithoutLeakingItThroughTheException() {
        String rawIp = "203.0.113.42";

        AgentActionException exception = assertInvalid("action=BLOCK_IP RaWiP=" + rawIp);

        assertThat(exception).hasMessageNotContaining(rawIp);
        assertThat(exception.getCause()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "action=DISABLE_SHORT_LINK action=BLOCK_IP",
            "action=DISABLE_SHORT_LINK reason=test",
            "action=DISABLE_SHORT_LINK unexpected",
            "action=DISABLE_SHORT_LINK =value",
            "action=",
            "action=DISABLE_SHORT_LINK timezone="
    })
    void failsClosedForDuplicateUnknownOrMalformedTokens(String message) {
        assertInvalid(message);
    }

    @Test
    void directiveDefensivelyCopiesAllowedWindowsAndNormalizesNull() {
        List<String> windows = new ArrayList<>(List.of("09:00-10:00"));
        RiskManualActionDirective directive = new RiskManualActionDirective(
                RiskPolicyAction.LIMIT_TIME_WINDOW,
                "UTC",
                windows
        );

        windows.clear();

        assertThat(directive.allowedWindows()).containsExactly("09:00-10:00");
        assertThatThrownBy(() -> directive.allowedWindows().add("10:00-11:00"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(new RiskManualActionDirective(
                RiskPolicyAction.DISABLE_SHORT_LINK,
                null,
                null
        ).allowedWindows()).isEmpty();
    }

    private AgentActionException assertInvalid(String message) {
        AgentActionException exception = assertThrows(
                AgentActionException.class,
                () -> parser.parse(message)
        );

        assertThat(exception.code()).isEqualTo(INVALID_CODE);
        assertThat(exception).hasMessage(INVALID_MESSAGE);
        return exception;
    }
}
