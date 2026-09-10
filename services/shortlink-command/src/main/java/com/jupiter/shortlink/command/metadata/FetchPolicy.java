package com.jupiter.shortlink.command.metadata;

import java.net.*;
import java.util.Locale;
import java.util.Set;

/** Public HTTP(S) only. DNS answers are checked in full and pinned to the resulting connection. */
public final class FetchPolicy {
    private FetchPolicy() {}

    public static URI uri(String value) {
        if (value == null
                || value.length() > 2048
                || value.chars().anyMatch(c -> c <= 32 || c == 127))
            throw new IllegalArgumentException("Invalid fetch URL");
        URI uri = URI.create(value);
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null)
            throw new IllegalArgumentException(
                    "Only absolute HTTP(S) without credentials is allowed");
        int expected = uri.getScheme().equals("https") ? 443 : 80;
        if (uri.getPort() != -1 && uri.getPort() != expected)
            throw new IllegalArgumentException("Non-standard fetch port denied");
        String host = host(uri).toLowerCase(Locale.ROOT);
        if (host.contains("%")
                || host.equals("localhost")
                || host.endsWith(".localhost")
                || host.endsWith(".local")
                || host.endsWith(".internal"))
            throw new IllegalArgumentException("Local hostname denied");
        return uri.getRawFragment() == null
                ? uri
                : URI.create(value.substring(0, value.indexOf('#')));
    }

    public static String host(URI uri) {
        String host = uri.getHost();
        return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    }

    public static void requirePublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress())
            throw new IllegalArgumentException("Non-public DNS answer denied");
        byte[] a = address.getAddress();
        int x = a[0] & 255, y = a[1] & 255;
        if (a.length == 4) {
            if (x == 0
                    || x == 10
                    || x == 127
                    || x >= 224
                    || (x == 100 && y >= 64 && y <= 127)
                    || (x == 169 && y == 254)
                    || (x == 172 && y >= 16 && y <= 31)
                    || (x == 192 && (y == 168 || y == 0 || (y == 88 && (a[2] & 255) == 99)))
                    || (x == 198 && (y == 18 || y == 19 || (y == 51 && (a[2] & 255) == 100)))
                    || (x == 203 && y == 0 && (a[2] & 255) == 113))
                throw new IllegalArgumentException("Special IPv4 range denied");
        } else if (a.length == 16) {
            // Allow native global unicast only; deny transition/special/documentation networks.
            if ((x & 224) != 32
                    || (x == 32
                            && y == 1
                            && ((a[2] & 255) < 2 || ((a[2] & 255) == 13 && (a[3] & 255) == 184)))
                    || (x == 32 && y == 2)
                    || (x == 63 && (y == 254 || y == 255)))
                throw new IllegalArgumentException("Special IPv6 range denied");
        } else throw new IllegalArgumentException("Unknown address family");
    }
}
