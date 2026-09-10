package com.jupiter.shortlink.risk;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class IpAddresses {
    private IpAddresses() {}

    public static InetAddress parse(String literal) {
        if (literal == null || literal.isEmpty() || literal.length() > 45)
            throw new IllegalArgumentException("Invalid IP");
        if (literal.indexOf(':') >= 0) {
            for (int i = 0; i < literal.length(); i++) {
                char ch = literal.charAt(i);
                if (!(ch >= '0' && ch <= '9')
                        && !(ch >= 'a' && ch <= 'f')
                        && !(ch >= 'A' && ch <= 'F')
                        && ch != ':'
                        && ch != '.') throw new IllegalArgumentException("Invalid IPv6");
            }
            // Keep the JDK's IPv6 and IPv4-mapped normalization. The numeric-only whitelist
            // excludes host names and zone identifiers before invoking the parser.
            try {
                return InetAddress.getByName(literal);
            } catch (UnknownHostException exception) {
                throw new IllegalArgumentException("Invalid IP", exception);
            }
        }

        byte[] address = new byte[4];
        int octet = 0, digits = 0, value = 0;
        for (int i = 0; i <= literal.length(); i++) {
            char ch = i == literal.length() ? '.' : literal.charAt(i);
            if (ch == '.') {
                if (digits == 0 || octet == address.length)
                    throw new IllegalArgumentException("Invalid IPv4");
                address[octet++] = (byte) value;
                digits = 0;
                value = 0;
            } else {
                if (ch < '0' || ch > '9' || digits == 3 || (digits > 0 && value == 0))
                    throw new IllegalArgumentException("Invalid IPv4");
                value = value * 10 + ch - '0';
                if (value > 255) throw new IllegalArgumentException("Invalid IPv4");
                digits++;
            }
        }
        if (octet != address.length) throw new IllegalArgumentException("Invalid IPv4");
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException exception) {
            // The address length is fixed above; retain the public unchecked failure contract.
            throw new IllegalArgumentException("Invalid IP", exception);
        }
    }

    public static String normalize(String literal) {
        return parse(literal).getHostAddress();
    }
}
