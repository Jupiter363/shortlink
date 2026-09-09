package com.jupiter.shortlink.risk;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Random;

class IpAddressesTest {
    @ParameterizedTest
    @CsvSource({
        "0.0.0.0,0.0.0.0",
        "255.255.255.255,255.255.255.255",
        "1.10.100.101,1.10.100.101",
        "::,0:0:0:0:0:0:0:0",
        "::1,0:0:0:0:0:0:0:1",
        "2001:DB8::1,2001:db8:0:0:0:0:0:1",
        "2001:0DB8:0000:0000:0000:0000:0000:0001,2001:db8:0:0:0:0:0:1",
        "00000:0:0:0:0:0:0:1,0:0:0:0:0:0:0:1",
        "::ffff:192.0.2.128,192.0.2.128",
        "0:0:0:0:0:FFFF:C000:0280,192.0.2.128",
        "::192.0.2.1,0:0:0:0:0:0:c000:201",
        "2001:db8::192.0.2.1,2001:db8:0:0:0:0:c000:201"
    })
    void canonicalAddressesPreserveJdkIpv6AndMappedIpv4(String input, String expected) {
        assertEquals(expected, IpAddresses.normalize(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "localhost", "example.com", "2130706433", "127.1", "127.0.1", "1.2.3.4.5",
        "1.2.3.", ".1.2.3", "1..2.3", "1.2.3.4.", "01.2.3.4", "1.02.3.4",
        "1.2.003.4", "1.2.3.00", "256.0.0.1", "1.2.3.999", "1000.0.0.1",
        "-1.2.3.4", "+1.2.3.4", "0x7f.0.0.1", " 1.2.3.4", "1.2.3.4 ",
        "1.2.3.4\n", "1.2.3.\t4", "\u0661.2.3.4", "[::1]", "::1%lo",
        "fe80::1%1", "2001:db8::g", "2001:db8:::1", "1:2:3:4:5:6:7:8:9",
        "1:2:3:4:5:6:7", "::ffff:192.0.2.999", "::ffff:127.1", ":", ":::",
        "2001:db8::1/64", "::1\n",
        "1111111111111111111111111111111111111111111111"
    })
    void invalidAndAmbiguousLiteralsAreRejected(String input) {
        assertThrows(IllegalArgumentException.class, () -> IpAddresses.parse(input));
    }

    @Test
    void everyOctetValueAndDeterministicAddressesMatchNetworkBytes() throws Exception {
        for (int octet = 0; octet <= 255; octet++) {
            byte[] expected = {(byte) octet, (byte) (255 - octet), (byte) octet, 0};
            String literal = octet + "." + (255 - octet) + "." + octet + ".0";
            assertArrayEquals(expected, IpAddresses.parse(literal).getAddress());
        }
        var random = new Random(42);
        for (int i = 0; i < 512; i++) {
            byte[] address = new byte[4];
            random.nextBytes(address);
            String literal = InetAddress.getByAddress(address).getHostAddress();
            assertEquals(literal, IpAddresses.normalize(literal));
            assertArrayEquals(address, IpAddresses.parse(literal).getAddress());
        }
        assertInstanceOf(Inet4Address.class, IpAddresses.parse("::ffff:192.0.2.128"));
    }
}
