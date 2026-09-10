package com.jupiter.shortlink.risk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

class TrustedProxyResolverTest {
    private final TrustedProxyResolver resolver =
            new TrustedProxyResolver(List.of("10.0.0.0/8", "2001:db8:feed::/48", "::1/128"));

    @Test
    void rightwardTrustStopsBeforeUntrustedOrMalformedLeftPrefix() {
        assertEquals("198.51.100.8", resolver.resolve("10.1.1.1",
                "attacker.example, 198.51.100.8, 10.2.2.2"));
        assertEquals("198.51.100.8", resolver.resolve("10.1.1.1",
                ",,198.51.100.8,10.2.2.2"));
        assertEquals("10.3.3.3", resolver.resolve("10.1.1.1", "10.3.3.3,10.2.2.2"));
        assertEquals("198.51.100.8", resolver.resolve("10.1.1.1", " \t198.51.100.8\r\n"));
    }

    @Test
    void untrustedPeerCannotSupplyAProxyChain() {
        assertEquals("198.51.100.8", resolver.resolve("198.51.100.8", "10.1.1.1"));
        assertEquals("198.51.100.8", resolver.resolve("198.51.100.8", "!".repeat(1025)));
        assertEquals("2001:db8:0:0:0:0:0:7", resolver.resolve("2001:db8::7", "10.1.1.1"));
    }

    @Test
    void mixedIpv6AndMappedIpv4RetainAddressFamilyAndTrust() {
        assertTrue(resolver.isTrusted("::ffff:10.1.2.3"));
        assertEquals("192.0.2.128", resolver.resolve("2001:DB8:FEED::1",
                "::FFFF:192.0.2.128, ::ffff:10.2.2.2"));
        assertEquals("2001:db8:0:0:0:0:0:7", resolver.resolve("::1",
                "2001:DB8::7,2001:db8:feed::2"));
        assertFalse(resolver.isTrusted("::10.1.2.3"));
    }

    @Test
    void malformedVisitedHopsCannotBeSkipped() {
        for (String header : List.of("attacker.example", "198.51.100.8,", ",10.2.2.2",
                "198.51.100.8,,10.2.2.2", "198.51.100.8,010.2.2.2")) {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve("10.1.1.1", header));
        }
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("host.example", null));
    }

    @Test
    void headerLengthAndHopBoundsApplyBeforeTrustTraversal() {
        String twenty = String.join(",", Collections.nCopies(20, "10.1.1.1"));
        assertEquals("10.1.1.1", resolver.resolve("10.1.1.1", twenty));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("10.1.1.1", twenty + ",198.51.100.8"));
        String header = "198.51.100.8";
        assertEquals(header, resolver.resolve("10.1.1.1", " ".repeat(1024 - header.length()) + header));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("10.1.1.1", " ".repeat(1025 - header.length()) + header));
        assertEquals("10.1.1.1", resolver.resolve("10.1.1.1", null));
        assertEquals("10.1.1.1", resolver.resolve("10.1.1.1", ""));
        // Entirely blank headers were ignored before the size check and remain ignored.
        assertEquals("10.1.1.1", resolver.resolve("10.1.1.1", " ".repeat(1025)));
    }

    @Test
    void partialAndFullSubnetPrefixesPreserveNetworkBoundaries() {
        var ipv4 = new TrustedProxyResolver(List.of("192.0.2.0/23"));
        assertTrue(ipv4.isTrusted("192.0.2.0"));
        assertTrue(ipv4.isTrusted("192.0.3.255"));
        assertFalse(ipv4.isTrusted("192.0.1.255"));
        assertFalse(ipv4.isTrusted("192.0.4.0"));
        var ipv6 = new TrustedProxyResolver(List.of("2001:db8:0:0:8000::/65"));
        assertTrue(ipv6.isTrusted("2001:db8::8000:0:0:0"));
        assertTrue(ipv6.isTrusted("2001:db8::ffff:ffff:ffff:ffff"));
        assertFalse(ipv6.isTrusted("2001:db8::7fff:ffff:ffff:ffff"));
        assertTrue(new TrustedProxyResolver(List.of("0.0.0.0/0")).isTrusted("255.255.255.255"));
        assertFalse(new TrustedProxyResolver(List.of("0.0.0.0/0")).isTrusted("::1"));
        assertTrue(new TrustedProxyResolver(List.of("::/0")).isTrusted("2001:db8::1"));
        assertFalse(new TrustedProxyResolver(List.of("::/0")).isTrusted("127.0.0.1"));
        assertTrue(new TrustedProxyResolver(List.of("192.0.2.1/32")).isTrusted("192.0.2.1"));
        assertFalse(new TrustedProxyResolver(List.of("192.0.2.1/32")).isTrusted("192.0.2.2"));
    }

    @Test
    void invalidCidrPrefixesFailClosed() {
        for (String cidr : List.of("10.0.0.0/-1", "10.0.0.0/33", "::1/129", "::1/",
                "::1/64/64", "::ffff:10.0.0.1/128", "example.com/8")) {
            assertThrows(IllegalArgumentException.class, () -> new TrustedProxyResolver(List.of(cidr)));
        }
    }
}
