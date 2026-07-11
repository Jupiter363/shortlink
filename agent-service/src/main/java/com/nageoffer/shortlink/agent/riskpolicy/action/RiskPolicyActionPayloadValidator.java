package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;
import com.nageoffer.shortlink.agent.riskcommon.safety.RiskIpSafety;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RiskPolicyActionPayloadValidator {

    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE = "Risk policy action payload is invalid";
    private static final Pattern IP_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern WINDOW_PATTERN = Pattern.compile(
            "(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})"
    );
    private static final Set<RiskPolicyAction> SUPPORTED_ACTIONS = Set.of(
            RiskPolicyAction.DISABLE_SHORT_LINK,
            RiskPolicyAction.LIMIT_TIME_WINDOW,
            RiskPolicyAction.BLOCK_IP
    );

    public RiskPolicyActionPayloadV1 validate(RiskPolicyActionPayloadV1 payload) {
        if (payload == null
                || payload.action() == null
                || !SUPPORTED_ACTIONS.contains(payload.action())
                || !hasText(payload.gid())
                || !hasText(payload.domain())
                || !hasText(payload.shortUri())
                || !hasText(payload.reason())
                || !hasText(payload.eventId())
                || containsRawIp(payload)) {
            throw invalidPayload();
        }

        switch (payload.action()) {
            case DISABLE_SHORT_LINK -> validateDisable(payload);
            case LIMIT_TIME_WINDOW -> validateTimeWindow(payload);
            case BLOCK_IP -> validateBlockIp(payload);
            default -> throw invalidPayload();
        }
        return payload;
    }

    private void validateDisable(RiskPolicyActionPayloadV1 payload) {
        if (hasText(payload.ipHash())
                || hasText(payload.timezone())
                || !payload.allowedWindows().isEmpty()) {
            throw invalidPayload();
        }
    }

    private void validateTimeWindow(RiskPolicyActionPayloadV1 payload) {
        if (hasText(payload.ipHash())
                || !hasText(payload.timezone())
                || payload.allowedWindows().isEmpty()) {
            throw invalidPayload();
        }
        try {
            ZoneId.of(payload.timezone());
        } catch (DateTimeException ignored) {
            throw invalidPayload();
        }

        List<TimeWindow> windows = new ArrayList<>(payload.allowedWindows().size());
        for (String value : payload.allowedWindows()) {
            windows.add(parseWindow(value));
        }
        windows.sort(Comparator.comparingInt(TimeWindow::startMinute));
        for (int index = 1; index < windows.size(); index++) {
            if (windows.get(index).startMinute() < windows.get(index - 1).endMinute()) {
                throw invalidPayload();
            }
        }
    }

    private void validateBlockIp(RiskPolicyActionPayloadV1 payload) {
        if (payload.ipHash() == null
                || !IP_HASH_PATTERN.matcher(payload.ipHash()).matches()
                || hasText(payload.timezone())
                || !payload.allowedWindows().isEmpty()) {
            throw invalidPayload();
        }
    }

    private TimeWindow parseWindow(String value) {
        Matcher matcher = WINDOW_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw invalidPayload();
        }
        int startMinute = minuteOfDay(matcher.group(1), matcher.group(2));
        int endMinute = minuteOfDay(matcher.group(3), matcher.group(4));
        if (startMinute < 0 || endMinute < 0 || startMinute >= endMinute) {
            throw invalidPayload();
        }
        return new TimeWindow(startMinute, endMinute);
    }

    private int minuteOfDay(String hourValue, String minuteValue) {
        int hour = Integer.parseInt(hourValue);
        int minute = Integer.parseInt(minuteValue);
        if (hour > 23 || minute > 59) {
            return -1;
        }
        return hour * 60 + minute;
    }

    private boolean containsRawIp(RiskPolicyActionPayloadV1 payload) {
        return containsRawIp(payload.gid())
                || containsRawIp(payload.domain())
                || containsRawIp(payload.shortUri())
                || containsRawIp(payload.ipHash())
                || containsRawIp(payload.timezone())
                || payload.allowedWindows().stream().anyMatch(this::containsRawIp)
                || containsRawIp(payload.reason())
                || containsRawIp(payload.eventId())
                || containsRawIp(payload.batchId());
    }

    private boolean containsRawIp(String value) {
        return RiskIpSafety.containsRawIpLiteral(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private AgentActionException invalidPayload() {
        return new AgentActionException(INVALID_CODE, INVALID_MESSAGE);
    }

    private record TimeWindow(int startMinute, int endMinute) {
    }
}
