package com.jupiter.shortlink.risk;

import java.net.InetAddress;
import java.util.List;

/** Trust walks from the actual peer toward the client, never from the untrusted leftmost header. */
public final class TrustedProxyResolver {
    private final List<Subnet> trusted;

    public TrustedProxyResolver(List<String> cidrs) {
        trusted = cidrs.stream().map(Subnet::parse).toList();
    }

    public boolean isTrusted(String address) {
        return isTrusted(IpAddresses.parse(address).getAddress());
    }

    private boolean isTrusted(byte[] address) {
        for (Subnet subnet : trusted) {
            if (subnet.contains(address)) return true;
        }
        return false;
    }

    public String resolve(String actualPeer, String forwardedFor) {
        InetAddress current = IpAddresses.parse(actualPeer);
        if (!isTrusted(current.getAddress()) || forwardedFor == null || forwardedFor.isBlank())
            return current.getHostAddress();
        if (forwardedFor.length() > 1024)
            throw new IllegalArgumentException("Proxy chain too long");
        int hops = 1;
        for (int i = 0; i < forwardedFor.length(); i++) {
            if (forwardedFor.charAt(i) == ',' && ++hops > 20)
                throw new IllegalArgumentException("Too many proxy hops");
        }
        int end = forwardedFor.length();
        while (true) {
            int comma = forwardedFor.lastIndexOf(',', end - 1);
            current = IpAddresses.parse(forwardedFor.substring(comma + 1, end).trim());
            if (comma < 0 || !isTrusted(current.getAddress())) return current.getHostAddress();
            end = comma;
        }
    }

    private record Subnet(byte[] address, int bits) {
        static Subnet parse(String cidr) {
            String[] pair = cidr.split("/", -1);
            byte[] address = IpAddresses.parse(pair[0]).getAddress();
            int bits = pair.length == 1 ? address.length * 8 : Integer.parseInt(pair[1]);
            if (pair.length > 2 || bits < 0 || bits > address.length * 8)
                throw new IllegalArgumentException("Invalid proxy CIDR");
            return new Subnet(address, bits);
        }

        boolean contains(byte[] candidate) {
            if (candidate.length != address.length) return false;
            int fullBytes = bits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != address[i]) return false;
            }
            int remaining = bits % 8;
            int mask = (0xff << (8 - remaining)) & 0xff;
            return remaining == 0
                    || (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }
}
