package com.jupiter.shortlink.risk;

import java.net.IDN;
import java.util.Locale;

/** Normalizes an HTTP authority without accepting URL syntax or performing DNS resolution. */
public final class HostNormalizer {
    public String normalize(String authority, String scheme) {
        if (authority == null
                || authority.isBlank()
                || authority.length() > 300
                || !authority.equals(authority.trim())
                || authority.chars().anyMatch(c -> c <= 32 || c >= 127)
                || authority.matches(".*[/\\\\?#@,].*"))
            throw new IllegalArgumentException("Invalid Host");
        String host;
        String port = null;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("Invalid IPv6 Host");
            host = "[" + IpAddresses.normalize(authority.substring(1, end)) + "]";
            if (authority.length() > end + 1) {
                if (authority.charAt(end + 1) != ':')
                    throw new IllegalArgumentException("Invalid Host port");
                port = authority.substring(end + 2);
            }
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
            port = colon < 0 ? null : authority.substring(colon + 1);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.isEmpty() || host.length() > 253)
                throw new IllegalArgumentException("Invalid Host name");
        }
        if (port != null) {
            if (!port.matches("[0-9]{1,5}"))
                throw new IllegalArgumentException("Invalid Host port");
            int value = Integer.parseInt(port);
            if (value < 1 || value > 65535) throw new IllegalArgumentException("Invalid Host port");
            if (("https".equals(scheme) && value == 443) || ("http".equals(scheme) && value == 80))
                return host;
            return host + ":" + value;
        }
        return host;
    }
}
