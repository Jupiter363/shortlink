package com.nageoffer.shortlink.agent.riskpolicy.action;

import com.nageoffer.shortlink.agent.harness.action.service.AgentActionException;
import com.nageoffer.shortlink.agent.riskcommon.model.RiskPolicyAction;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RiskManualActionDirectiveParser {

    private static final String INVALID_CODE = "ACTION_PAYLOAD_INVALID";
    private static final String INVALID_MESSAGE =
            "Risk manual action directive payload is invalid";
    private static final String ACTION = "action";
    private static final String TIMEZONE = "timezone";
    private static final String ALLOWED_WINDOWS = "allowedWindows";
    private static final Set<String> SUPPORTED_KEYS =
            Set.of(ACTION, TIMEZONE, ALLOWED_WINDOWS);
    private static final Pattern WINDOW_PATTERN =
            Pattern.compile("(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})");

    public Optional<RiskManualActionDirective> parse(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        Map<String, String> values = parseValues(message);
        String actionValue = values.get(ACTION);
        if (actionValue == null) {
            return Optional.empty();
        }

        RiskPolicyAction action = supportedAction(actionValue);
        if (action == null) {
            return Optional.empty();
        }

        if (action == RiskPolicyAction.LIMIT_TIME_WINDOW) {
            return Optional.of(parseLimitTimeWindow(values));
        }
        if (values.size() != 1) {
            throw invalidPayload();
        }
        return Optional.of(new RiskManualActionDirective(action, null, List.of()));
    }

    private Map<String, String> parseValues(String message) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : message.strip().split("\\s+")) {
            int separator = token.indexOf('=');
            if (separator <= 0
                    || separator != token.lastIndexOf('=')
                    || separator == token.length() - 1) {
                throw invalidPayload();
            }

            String key = token.substring(0, separator);
            String value = token.substring(separator + 1);
            if (key.equalsIgnoreCase("rawIp") || !SUPPORTED_KEYS.contains(key)) {
                throw invalidPayload();
            }
            if (values.putIfAbsent(key, value) != null) {
                throw invalidPayload();
            }
        }
        return values;
    }

    private RiskPolicyAction supportedAction(String value) {
        return switch (value) {
            case "DISABLE_SHORT_LINK" -> RiskPolicyAction.DISABLE_SHORT_LINK;
            case "LIMIT_TIME_WINDOW" -> RiskPolicyAction.LIMIT_TIME_WINDOW;
            case "BLOCK_IP" -> RiskPolicyAction.BLOCK_IP;
            default -> null;
        };
    }

    private RiskManualActionDirective parseLimitTimeWindow(Map<String, String> values) {
        if (values.size() != 3) {
            throw invalidPayload();
        }

        String timezone = values.get(TIMEZONE);
        String allowedWindows = values.get(ALLOWED_WINDOWS);
        if (timezone == null || allowedWindows == null) {
            throw invalidPayload();
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException ignored) {
            throw invalidPayload();
        }

        List<TimeWindow> windows = parseWindows(allowedWindows);
        windows.sort(Comparator.comparingInt(TimeWindow::startMinute));
        for (int index = 1; index < windows.size(); index++) {
            if (windows.get(index).startMinute() < windows.get(index - 1).endMinute()) {
                throw invalidPayload();
            }
        }
        return new RiskManualActionDirective(
                RiskPolicyAction.LIMIT_TIME_WINDOW,
                timezone,
                windows.stream().map(TimeWindow::text).toList()
        );
    }

    private List<TimeWindow> parseWindows(String value) {
        String[] segments = value.split("\\|", -1);
        List<TimeWindow> windows = new ArrayList<>(segments.length);
        for (String segment : segments) {
            Matcher matcher = WINDOW_PATTERN.matcher(segment);
            if (!matcher.matches()) {
                throw invalidPayload();
            }

            int startMinute = minuteOfDay(matcher.group(1), matcher.group(2));
            int endMinute = minuteOfDay(matcher.group(3), matcher.group(4));
            if (startMinute < 0 || endMinute < 0 || startMinute >= endMinute) {
                throw invalidPayload();
            }
            windows.add(new TimeWindow(startMinute, endMinute, segment));
        }
        return windows;
    }

    private int minuteOfDay(String hourText, String minuteText) {
        int hour = Integer.parseInt(hourText);
        int minute = Integer.parseInt(minuteText);
        if (hour > 23 || minute > 59) {
            return -1;
        }
        return hour * 60 + minute;
    }

    private AgentActionException invalidPayload() {
        return new AgentActionException(INVALID_CODE, INVALID_MESSAGE);
    }

    private record TimeWindow(int startMinute, int endMinute, String text) {
    }
}
