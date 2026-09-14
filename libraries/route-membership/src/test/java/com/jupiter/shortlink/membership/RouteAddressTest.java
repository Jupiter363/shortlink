package com.jupiter.shortlink.membership;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteAddressTest {
    @Test void preservesShortCodeCaseAndSeparatesHostFromShortCode() {
        assertNotEquals(new RouteAddress("a.example", "Ab"), new RouteAddress("a.example", "ab"));
        assertNotEquals(new RouteAddress("a.example", "b"), new RouteAddress("b.example", "a"));
        assertEquals("s.example:8080", new RouteAddress("s.example:8080", "legacy7").domainNorm());
    }

    @Test void requiresExistingHostNormalizerOutputWithoutGuessingAScheme() {
        assertThrows(IllegalArgumentException.class, () -> new RouteAddress("S.EXAMPLE", "a"));
        assertThrows(IllegalArgumentException.class, () -> new RouteAddress("s.example.", "a"));
        assertThrows(IllegalArgumentException.class, () -> new RouteAddress("s.example/path", "a"));
        assertThrows(IllegalArgumentException.class, () -> new RouteAddress("s.example", "a/b"));
        assertThrows(IllegalArgumentException.class, () -> new RouteAddress("s.example", ""));
        assertEquals("s.example:80", new RouteAddress("s.example:80", "a").domainNorm());
    }
}
