package com.jupiter.shortlink.risk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class RequestContextTest {
    @Test
    void onlyTrustedRightHandProxySuffixIsRemoved() {
        var resolver = new TrustedProxyResolver(List.of("10.0.0.0/8", "::1/128"));
        assertEquals("203.0.113.9", resolver.resolve("203.0.113.9", "10.0.0.2"));
        assertEquals(
                "198.51.100.5", resolver.resolve("10.0.0.2", "1.1.1.1, 198.51.100.5, 10.1.2.3"));
        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve("10.0.0.2", "attacker.example"));
    }

    @Test
    void numericIpParsingNeverTreatsDnsOrAlternativeIpv4AsAnAddress() {
        for (String value :
                List.of("localhost", "2130706433", "0177.0.0.1", "127.1", "::1%lo", "1.2.3.999"))
            assertThrows(IllegalArgumentException.class, () -> IpAddresses.parse(value));
        assertEquals("127.0.0.1", IpAddresses.normalize("127.0.0.1"));
    }

    @Test
    void hostDefaultPortsDependOnSchemeAndShortcodesAreNotPartOfNormalization() {
        var normalizer = new HostNormalizer();
        assertEquals("example.test", normalizer.normalize("EXAMPLE.test:443", "https"));
        assertEquals("example.test:443", normalizer.normalize("EXAMPLE.test:443", "http"));
        assertEquals("example.test:80", normalizer.normalize("example.test:80", "https"));
        for (String bad : List.of("a@b", "a/b", "a:0", "a:65536", "a,b", " a", "a\\b"))
            assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(bad, "https"));
    }
}
