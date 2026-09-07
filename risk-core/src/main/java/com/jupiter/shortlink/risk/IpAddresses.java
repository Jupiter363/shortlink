package com.jupiter.shortlink.risk;

import java.net.InetAddress;
import java.net.UnknownHostException;

public final class IpAddresses {
    private IpAddresses() {}

    public static InetAddress parse(String literal) {
        if (literal == null || literal.isEmpty() || literal.length() > 45)
            throw new IllegalArgumentException("Invalid IP");
        if (literal.indexOf(':') >= 0) {
            if (!literal.matches("[0-9A-Fa-f:.]+"))
                throw new IllegalArgumentException("Invalid IPv6");
        } else {
            String[] parts = literal.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4");
            for (String part : parts) {
                if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255)
                    throw new IllegalArgumentException("Invalid IPv4");
            }
        }
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("Invalid IP", exception);
        }
    }

    public static String normalize(String literal) {
        return parse(literal).getHostAddress();
    }
}
