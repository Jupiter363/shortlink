package com.jupiter.shortlink.contract.geo;

import static com.jupiter.shortlink.contract.geo.IpAddressPolicy.Disposition.INVALID;
import static com.jupiter.shortlink.contract.geo.IpAddressPolicy.Disposition.NON_PUBLIC;
import static com.jupiter.shortlink.contract.geo.IpAddressPolicy.Disposition.PUBLIC;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IpAddressPolicyTest {
    @ParameterizedTest(name = "IPv4 special range boundary {index}")
    @CsvSource({
        "0.0.0.0,0.255.255.255,,1.0.0.0",
        "10.0.0.0,10.255.255.255,9.255.255.255,11.0.0.0",
        "100.64.0.0,100.127.255.255,100.63.255.255,100.128.0.0",
        "127.0.0.0,127.255.255.255,126.255.255.255,128.0.0.0",
        "169.254.0.0,169.254.255.255,169.253.255.255,169.255.0.0",
        "172.16.0.0,172.31.255.255,172.15.255.255,172.32.0.0",
        "192.0.0.0,192.0.0.255,191.255.255.255,192.0.1.0",
        "192.0.2.0,192.0.2.255,192.0.1.255,192.0.3.0",
        "192.31.196.0,192.31.196.255,192.31.195.255,192.31.197.0",
        "192.52.193.0,192.52.193.255,192.52.192.255,192.52.194.0",
        "192.88.99.0,192.88.99.255,192.88.98.255,192.88.100.0",
        "192.168.0.0,192.168.255.255,192.167.255.255,192.169.0.0",
        "192.175.48.0,192.175.48.255,192.175.47.255,192.175.49.0",
        "198.18.0.0,198.19.255.255,198.17.255.255,198.20.0.0",
        "198.51.100.0,198.51.100.255,198.51.99.255,198.51.101.0",
        "203.0.113.0,203.0.113.255,203.0.112.255,203.0.114.0",
        "224.0.0.0,255.255.255.255,223.255.255.255,"
    })
    void excludesWholeSpecialPurposeRangesWithoutExcludingAdjacentClientAddresses(
            String first, String last, String before, String after) {
        assertClassification(first, NON_PUBLIC, 4);
        assertClassification(last, NON_PUBLIC, 4);
        if (before != null) assertClassification(before, PUBLIC, 4);
        if (after != null) assertClassification(after, PUBLIC, 4);
    }

    @ParameterizedTest(name = "IPv4 protocol and reserved address {index}")
    @ValueSource(strings = {"192.0.0.9", "192.0.0.10", "192.0.0.170", "192.0.0.171", "192.88.99.2",
            "239.255.255.255", "240.0.0.0", "255.255.255.254"})
    void conservativelyExcludesProtocolAnycastAndReservedAddresses(String input) {
        assertClassification(input, NON_PUBLIC, 4);
    }

    @ParameterizedTest(name = "IPv6 assigned block boundary {index}")
    @CsvSource({
        "2001:200::,2001:3ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:400::,2001:5ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:600::,2001:7ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:800::,2001:bff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:c00::,2001:dff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:e00::,2001:fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:1200::,2001:13ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:1400::,2001:17ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:1800::,2001:19ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:1a00::,2001:1bff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:1c00::,2001:1fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:2000::,2001:3fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4000::,2001:41ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4200::,2001:43ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4400::,2001:45ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4600::,2001:47ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4800::,2001:49ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4a00::,2001:4bff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:4c00::,2001:4dff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:5000::,2001:5fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:8000::,2001:9fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:a000::,2001:afff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2001:b000::,2001:bfff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2003::,2003:3fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2400::,240f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2410::,241f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2600::,260f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2610::,2610:1ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2620::,2620:1ff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2630::,263f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2800::,280f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2a00::,2a0f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2a10::,2a1f:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
        "2c00::,2c0f:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    })
    void acceptsBothEndsOfIanaRirAllocatedIpv6Blocks(String first, String last) {
        assertClassification(first, PUBLIC, 16);
        assertClassification(last, PUBLIC, 16);
    }

    @ParameterizedTest(name = "IPv6 special or unallocated address {index}")
    @ValueSource(strings = {
        "::", "::1", "::8.8.8.8", "::ffff:0:8.8.8.8",
        "64:ff9b::8.8.8.8", "64:ff9b:1::1", "100::", "100:0:0:1::1",
        "1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2000::",
        "2001::", "2001:1::1", "2001:1::2", "2001:1::3", "2001:2::1",
        "2001:3::1", "2001:4:112::1", "2001:10::1", "2001:20::1", "2001:30::1",
        "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff", "2001:db8::",
        "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", "2001:1000::",
        "2001:11ff:ffff:ffff:ffff:ffff:ffff:ffff", "2001:4e00::",
        "2001:4fff:ffff:ffff:ffff:ffff:ffff:ffff", "2001:6000::",
        "2001:7fff:ffff:ffff:ffff:ffff:ffff:ffff", "2001:c000::", "2002::",
        "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2003:4000::",
        "23ff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2420::",
        "25ff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2610:200::", "2611::", "2612::", "261f:ffff::1",
        "2620:4f:8000::", "2620:4f:8000:ffff:ffff:ffff:ffff:ffff", "2620:200::", "2621::", "2622::",
        "262f:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "2640::", "2810::", "2a20::", "2c10::",
        "2d00::", "3000::", "3ffe::", "3fff::", "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff",
        "3fff:1000::", "4000::", "5f00::", "fc00::", "fdff:ffff::1", "fe00::", "fe7f:ffff::1",
        "fe80::", "febf:ffff::1", "fec0::", "feff:ffff::1", "ff00::", "ffff:ffff::1"
    })
    void excludesSpecialIpv6SpaceAndGapsInTheGlobalUnicastAllocations(String input) {
        assertClassification(input, NON_PUBLIC, 16);
    }

    @ParameterizedTest(name = "IPv6 public neighbor of special range {index}")
    @ValueSource(strings = {"2001:db7:ffff:ffff:ffff:ffff:ffff:ffff", "2001:db9::",
            "2620:4f:7fff:ffff:ffff:ffff:ffff:ffff", "2620:4f:8001::"})
    void doesNotOverExcludeAssignedIpv6NeighborsOfSpecialRanges(String input) {
        assertClassification(input, PUBLIC, 16);
    }

    @ParameterizedTest(name = "IPv4 mapped address normalization {index}")
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "0.0.0.0", "10.0.0.1", "100.64.0.1",
            "127.0.0.1", "169.254.1.1", "172.31.255.255", "192.0.2.1", "192.168.1.1",
            "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255"})
    void mappedIpv6UsesTheSameFourBytesAndDispositionAsIpv4(String ipv4) {
        var expected = IpAddressPolicy.parse(ipv4);
        String hex = HexFormat.of().formatHex(expected.address());
        for (String mapped : new String[] {"::ffff:" + ipv4, "0:0:0:0:0:FFFF:" + ipv4,
                "::FFFF:" + hex.substring(0, 4) + ":" + hex.substring(4)}) {
            var actual = IpAddressPolicy.parse(mapped);
            assertEquals(expected.disposition(), actual.disposition());
            assertArrayEquals(expected.address(), actual.address());
        }
    }

    @Test
    void compressedExpandedUppercaseAndDottedSuffixIpv6ProduceTheSameNetworkBytes() {
        byte[] expected = HexFormat.of().parseHex("2606abcd470000000000000001010101");
        for (String input : new String[] {"2606:abcd:4700::101:101", "2606:ABCD:4700::0101:0101",
                "2606:abcd:4700:0:0:0:101:101", "2606:abcd:4700::1.1.1.1",
                "2606:abcd:4700:0000:0000:0000:1.1.1.1"}) {
            var parsed = IpAddressPolicy.parse(input);
            assertEquals(PUBLIC, parsed.disposition());
            assertArrayEquals(expected, parsed.address());
        }
        assertClassification("ffff:ffff:ffff:ffff:ffff:ffff:255.255.255.255", NON_PUBLIC, 16);
    }

    @ParameterizedTest(name = "Invalid literal {index}")
    @NullAndEmptySource
    @ValueSource(strings = {
        " ", " 8.8.8.8", "8.8.8.8 ", "8.8.8.8\n", "\t::1", "::1\r", "8.8.8.8\u0000",
        "localhost", "example.com", "example.com:443", "https://8.8.8.8/", "8.8.8.8/32",
        "8.8.8.8:443", "[8.8.8.8]", "127", "127.1", "127.0.1", "2130706433", "0x7f000001",
        "0177.0.0.1", "127.00.0.1", "0x7f.0.0.1", "+127.0.0.1", "-1.0.0.1",
        "256.0.0.1", "1.2.3.999", "1.2.3.4.5", "1.2.3.4.", ".1.2.3.4", "1..2.3",
        "１.２.３.４", "١.٢.٣.٤", "[::1]", "[::1]:80", "fe80::1%eth0", "fe80::1%1",
        "fe80::1%25eth0", "2606:4700::1/64", ":", ":::1", "1:::2", "1::2::3",
        "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8",
        ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "2606:10000::1", "2606:gggg::1", "2606:+1::1",
        "::ffff:127.0.0.1:80", "::ffff:127.00.0.1", "::ffff:127.1", "::ffff:0x7f000001",
        "1:2:3:4:5:6::1.2.3.4", "1:2:3:4:5:6:7:1.2.3.4", "1.2.3.4::", "2606::１"
    })
    void rejectsMalformedLiteralsAndHostOrEndpointSyntaxWithoutResolvingNames(String input) {
        assertClassification(input, INVALID, 0);
    }

    @Test
    void rejectsOversizeInputAndKeepsReturnedAddressesImmutableWithoutEchoingIp() {
        assertClassification("f".repeat(100_000), INVALID, 0);
        byte[] address = {8, 8, 8, 8};
        var parsed = new IpAddressPolicy.Parsed(address, PUBLIC);
        address[0] = 127;
        byte[] returned = parsed.address();
        returned[1] = 0;
        assertArrayEquals(new byte[] {8, 8, 8, 8}, parsed.address());
        assertEquals("Parsed[addressBytes=4, disposition=PUBLIC]", parsed.toString());
        assertFalse(parsed.toString().contains("8.8.8.8"));
    }

    private static void assertClassification(
            String input, IpAddressPolicy.Disposition disposition, int byteLength) {
        var parsed = assertDoesNotThrow(() -> IpAddressPolicy.parse(input));
        assertEquals(disposition, parsed.disposition());
        assertEquals(byteLength, parsed.address().length);
    }
}
