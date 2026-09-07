package com.jupiter.shortlink.risk;

import java.util.List;

/** Trust walks from the actual peer toward the client, never from the untrusted leftmost header. */
public final class TrustedProxyResolver {
    private final List<Subnet> trusted;

    public TrustedProxyResolver(List<String> cidrs) {
        trusted = cidrs.stream().map(Subnet::parse).toList();
    }

    public boolean isTrusted(String address) {
        byte[] bytes = IpAddresses.parse(address).getAddress();
        return trusted.stream().anyMatch(subnet -> subnet.contains(bytes));
    }

    public String resolve(String actualPeer, String forwardedFor) {
        String current = IpAddresses.normalize(actualPeer);
        if (!isTrusted(current) || forwardedFor == null || forwardedFor.isBlank()) return current;
        if (forwardedFor.length() > 1024)
            throw new IllegalArgumentException("Proxy chain too long");
        String[] hops = forwardedFor.split(",", -1);
        if (hops.length > 20) throw new IllegalArgumentException("Too many proxy hops");
        for (int i = hops.length - 1; i >= 0 && isTrusted(current); i--)
            current = IpAddresses.normalize(hops[i].trim());
        return current;
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
            for (int i = 0; i < bits; i++) {
                int mask = 1 << (7 - i % 8);
                if ((candidate[i / 8] & mask) != (address[i / 8] & mask)) return false;
            }
            return true;
        }
    }
}
