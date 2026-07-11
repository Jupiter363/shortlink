package com.nageoffer.shortlink.agent.riskcommon.safety;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RiskIpSafety {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern IPV4_LITERAL_PATTERN = Pattern.compile(
            "(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])"
    );
    private static final Pattern IPV4_EXACT_PATTERN = Pattern.compile(
            "(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})"
    );
    private static final Pattern ADDRESS_RUN_PATTERN = Pattern.compile("[0-9A-Fa-f:.%]+");
    private static final Pattern MASKED_IPV4_PATTERN = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.\\*\\.\\*");
    private static final Pattern MASKED_IPV6_PATTERN = Pattern.compile(
            "(?i)[0-9a-f]{1,4}:[0-9a-f]{1,4}:\\*:\\*"
    );
    private static final int MAX_IPV6_RUN_LENGTH = 128;

    private RiskIpSafety() {
    }

    public static boolean containsRawIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        Matcher ipv4Matcher = IPV4_LITERAL_PATTERN.matcher(value);
        while (ipv4Matcher.find()) {
            if (validIpv4(ipv4Matcher)) {
                return true;
            }
        }
        Matcher matcher = ADDRESS_RUN_PATTERN.matcher(value);
        while (matcher.find()) {
            if (containsIpv6Literal(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAllowedMaskedIp(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            return false;
        }
        Matcher ipv4 = MASKED_IPV4_PATTERN.matcher(value);
        if (ipv4.matches()) {
            return validOctet(ipv4.group(1)) && validOctet(ipv4.group(2));
        }
        return MASKED_IPV6_PATTERN.matcher(value).matches();
    }

    public static String maskIp(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String candidate = normalizeAddressCandidate(value.trim());
        Matcher ipv4 = IPV4_EXACT_PATTERN.matcher(candidate);
        if (ipv4.matches() && validIpv4(ipv4)) {
            return ipv4.group(1) + "." + ipv4.group(2) + ".*.*";
        }
        InetAddress address = parseIpv6(candidate);
        if (address == null) {
            return "***";
        }
        byte[] bytes = address.getAddress();
        int first = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
        int second = ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
        return Integer.toHexString(first) + ":" + Integer.toHexString(second) + ":*:*";
    }

    public static String sanitizeIpLiterals(String value) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        String ipv4Safe = IPV4_LITERAL_PATTERN.matcher(value)
                .replaceAll(match -> validIpv4(match)
                        ? match.group(1) + "." + match.group(2) + ".*.*"
                        : match.group());
        Matcher matcher = ADDRESS_RUN_PATTERN.matcher(ipv4Safe);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String candidate = normalizeAddressCandidate(matcher.group());
            if (!looksLikeIpv6(candidate)) {
                continue;
            }
            InetAddress address = parseIpv6(candidate);
            if (address == null) {
                continue;
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(maskIp(candidate)));
        }
        matcher.appendTail(result);
        String sanitized = result.toString();
        return containsRawIpLiteral(sanitized) ? REDACTED : sanitized;
    }

    private static boolean containsIpv6Literal(String run) {
        if (run == null) {
            return false;
        }
        String normalized = normalizeAddressCandidate(run);
        if (!looksLikeIpv6(normalized)) {
            return false;
        }
        if (normalized.length() > MAX_IPV6_RUN_LENGTH) {
            return true;
        }
        return parseIpv6(normalized) != null
                || normalized.contains("::")
                || colonCount(normalized) >= 7;
    }

    private static boolean looksLikeIpv6(String value) {
        return value != null
                && (value.contains("::") || colonCount(value) >= 7);
    }

    private static InetAddress parseIpv6(String value) {
        if (value == null || value.isBlank() || value.indexOf(':') < 0 || value.indexOf('*') >= 0) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(value);
            return address instanceof Inet6Address ? address : null;
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private static String normalizeAddressCandidate(String value) {
        String candidate = value;
        while (candidate.startsWith(".")) {
            candidate = candidate.substring(1);
        }
        while (candidate.endsWith(".")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        int zoneIndex = candidate.indexOf('%');
        return zoneIndex < 0 ? candidate : candidate.substring(0, zoneIndex);
    }

    private static int colonCount(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == ':') {
                count++;
            }
        }
        return count;
    }

    private static boolean validIpv4(MatchResult matcher) {
        return validOctet(matcher.group(1))
                && validOctet(matcher.group(2))
                && validOctet(matcher.group(3))
                && validOctet(matcher.group(4));
    }

    private static boolean validOctet(String value) {
        try {
            int octet = Integer.parseInt(value);
            return octet >= 0 && octet <= 255;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
