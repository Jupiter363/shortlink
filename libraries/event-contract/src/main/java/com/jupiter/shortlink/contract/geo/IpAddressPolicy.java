package com.jupiter.shortlink.contract.geo;

import java.util.Arrays;
import java.util.Objects;

/**
 * Parses bare IP literals locally and selects addresses suitable for public GeoIP lookup.
 *
 * <p>This is a conservative classification, not a reachability or authorization check. All
 * special-purpose IPv4 ranges, and IPv6 ranges not allocated to RIRs, are excluded. Globally
 * reachable protocol anycast and translation prefixes are deliberately excluded as well. Only
 * IPv4-mapped IPv6 is normalized, to the underlying four IPv4 bytes before classification.
 *
 * <p>Policy snapshot checked 2026-09-13 against the IANA registries below. New allocations require
 * an explicit policy update; there is no DNS, HTTP, interface, or routing-table lookup.
 *
 * @see <a href="https://www.iana.org/assignments/iana-ipv4-special-registry">IPv4 special purposes</a>
 * @see <a href="https://www.iana.org/assignments/iana-ipv6-special-registry">IPv6 special purposes</a>
 * @see <a href="https://www.iana.org/assignments/ipv4-address-space">IPv4 address space</a>
 * @see <a href="https://www.iana.org/assignments/ipv6-unicast-address-assignments">IPv6 allocations</a>
 */
public final class IpAddressPolicy {
    private static final int MAX_LITERAL_LENGTH = 45;
    private static final Parsed INVALID_RESULT = new Parsed(new byte[0], Disposition.INVALID);

    // Inclusive IPv4 ranges. Protocol anycast exceptions stay excluded for GeoIP purposes.
    private static final long[][] IPV4_EXCLUDED_RANGES = {
        {0x00000000L, 0x00ffffffL}, // 0.0.0.0/8: this network
        {0x0a000000L, 0x0affffffL}, // 10.0.0.0/8: private
        {0x64400000L, 0x647fffffL}, // 100.64.0.0/10: shared / CGNAT
        {0x7f000000L, 0x7fffffffL}, // 127.0.0.0/8: loopback
        {0xa9fe0000L, 0xa9feffffL}, // 169.254.0.0/16: link-local
        {0xac100000L, 0xac1fffffL}, // 172.16.0.0/12: private
        {0xc0000000L, 0xc00000ffL}, // 192.0.0.0/24: protocol assignments
        {0xc0000200L, 0xc00002ffL}, // 192.0.2.0/24: TEST-NET-1
        {0xc01fc400L, 0xc01fc4ffL}, // 192.31.196.0/24: AS112
        {0xc034c100L, 0xc034c1ffL}, // 192.52.193.0/24: AMT
        {0xc0586300L, 0xc05863ffL}, // 192.88.99.0/24: deprecated relay anycast
        {0xc0a80000L, 0xc0a8ffffL}, // 192.168.0.0/16: private
        {0xc0af3000L, 0xc0af30ffL}, // 192.175.48.0/24: AS112
        {0xc6120000L, 0xc613ffffL}, // 198.18.0.0/15: benchmarking
        {0xc6336400L, 0xc63364ffL}, // 198.51.100.0/24: TEST-NET-2
        {0xcb007100L, 0xcb0071ffL}, // 203.0.113.0/24: TEST-NET-3
        {0xe0000000L, 0xffffffffL} // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved
    };

    // Inclusive first-32-bit intervals, merging only adjacent IANA allocations to RIRs.
    // Merely testing 2000::/3 would also admit space reserved for future allocation.
    private static final long[][] IPV6_ALLOCATED_RANGES = {
        {0x20010200L, 0x20010fffL}, // 2001:200:: through 2001:fff::
        {0x20011200L, 0x20014dffL}, // 2001:1200:: through 2001:4dff::
        {0x20015000L, 0x20015fffL}, // 2001:5000::/20
        {0x20018000L, 0x2001bfffL}, // 2001:8000::/19, a000::/20, b000::/20
        {0x20030000L, 0x20033fffL}, // 2003::/18
        {0x24000000L, 0x241fffffL}, // 2400::/12, 2410::/12
        {0x26000000L, 0x261001ffL}, // 2600::/12, 2610::/23
        {0x26200000L, 0x262001ffL}, // 2620::/23
        {0x26300000L, 0x263fffffL}, // 2630::/12
        {0x28000000L, 0x280fffffL}, // 2800::/12
        {0x2a000000L, 0x2a1fffffL}, // 2a00::/12, 2a10::/12
        {0x2c000000L, 0x2c0fffffL} // 2c00::/12
    };

    private IpAddressPolicy() {}

    public enum Disposition {
        PUBLIC,
        NON_PUBLIC,
        INVALID
    }

    /** Address bytes use network order and are copied on construction and access. */
    public record Parsed(byte[] address, Disposition disposition) {
        public Parsed {
            Objects.requireNonNull(address, "Address bytes are required");
            Objects.requireNonNull(disposition, "Disposition is required");
            if (disposition == Disposition.INVALID
                    ? address.length != 0
                    : address.length != 4 && address.length != 16) {
                throw new IllegalArgumentException("Address length does not match disposition");
            }
            address = address.clone();
        }

        @Override
        public byte[] address() {
            return address.clone();
        }

        @Override
        public String toString() {
            return "Parsed[addressBytes=" + address.length + ", disposition=" + disposition + "]";
        }
    }

    /**
     * Accepts strict dotted-decimal IPv4 or IPv6, including a final dotted-decimal IPv4 suffix.
     * Does not trim whitespace or accept hostnames, URI brackets, ports, scope IDs, or legacy IPv4
     * integer, octal, hexadecimal, and abbreviated forms. Invalid input is never echoed.
     */
    public static Parsed parse(String input) {
        if (input == null || input.isEmpty() || input.length() > MAX_LITERAL_LENGTH) {
            return INVALID_RESULT;
        }
        byte[] address = input.indexOf(':') >= 0 ? parseIpv6(input) : parseIpv4(input, 0, input.length());
        if (address == null) return INVALID_RESULT;
        if (isIpv4Mapped(address)) address = Arrays.copyOfRange(address, 12, 16);
        boolean publicAddress =
                address.length == 4
                        ? !contains(IPV4_EXCLUDED_RANGES, first32Bits(address))
                        : isPublicIpv6(address);
        return new Parsed(address, publicAddress ? Disposition.PUBLIC : Disposition.NON_PUBLIC);
    }

    private static byte[] parseIpv4(String input, int start, int end) {
        byte[] address = new byte[4];
        int cursor = start;
        for (int octet = 0; octet < address.length; octet++) {
            int value = 0;
            int digits = 0;
            int octetStart = cursor;
            while (cursor < end && input.charAt(cursor) != '.') {
                char character = input.charAt(cursor++);
                if (character < '0' || character > '9' || ++digits > 3) return null;
                value = value * 10 + character - '0';
            }
            if (digits == 0 || value > 255 || (digits > 1 && input.charAt(octetStart) == '0')) {
                return null;
            }
            address[octet] = (byte) value;
            if (octet == 3) return cursor == end ? address : null;
            if (cursor == end) return null;
            cursor++; // Skip the dot separating two required octets.
        }
        return null;
    }

    private static byte[] parseIpv6(String input) {
        byte[] address = new byte[16];
        int cursor = 0;
        int written = 0;
        int compression = -1;
        if (input.charAt(0) == ':') {
            if (input.length() < 2 || input.charAt(1) != ':') return null;
            compression = 0;
            cursor = 2;
        }
        while (cursor < input.length()) {
            if (written >= address.length) return null;
            int start = cursor;
            int value = 0;
            int digits = 0;
            boolean dottedSuffix = false;
            while (cursor < input.length() && input.charAt(cursor) != ':') {
                char character = input.charAt(cursor);
                if (character == '.') {
                    dottedSuffix = true;
                    break;
                }
                int digit = hexDigit(character);
                if (digit < 0 || ++digits > 4) return null;
                value = (value << 4) | digit;
                cursor++;
            }
            if (dottedSuffix) {
                if (written > 12) return null;
                byte[] ipv4 = parseIpv4(input, start, input.length());
                if (ipv4 == null) return null;
                System.arraycopy(ipv4, 0, address, written, ipv4.length);
                written += ipv4.length;
                break;
            }
            if (digits == 0) return null;
            address[written++] = (byte) (value >>> 8);
            address[written++] = (byte) value;
            if (cursor == input.length()) break;
            if (++cursor == input.length()) return null; // A single trailing colon is invalid.
            if (input.charAt(cursor) == ':') {
                if (compression >= 0) return null;
                compression = written;
                cursor++;
            }
        }
        if (compression < 0) return written == address.length ? address : null;
        if (written == address.length) return null; // "::" must replace at least one hextet.
        int gap = address.length - written;
        System.arraycopy(address, compression, address, compression + gap, written - compression);
        Arrays.fill(address, compression, compression + gap, (byte) 0);
        return address;
    }

    private static int hexDigit(char character) {
        if (character >= '0' && character <= '9') return character - '0';
        if (character >= 'a' && character <= 'f') return character - 'a' + 10;
        if (character >= 'A' && character <= 'F') return character - 'A' + 10;
        return -1;
    }

    private static boolean isIpv4Mapped(byte[] address) {
        if (address.length != 16 || address[10] != (byte) 0xff || address[11] != (byte) 0xff) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) return false;
        }
        return true;
    }

    private static boolean isPublicIpv6(byte[] address) {
        long prefix = first32Bits(address);
        if (!contains(IPV6_ALLOCATED_RANGES, prefix)) return false;
        if (prefix == 0x20010db8L) return false; // 2001:db8::/32: documentation
        // 2620:4f:8000::/48 is the special-purpose AS112 service, not ordinary client space.
        return !(prefix == 0x2620004fL && address[4] == (byte) 0x80 && address[5] == 0);
    }

    private static long first32Bits(byte[] address) {
        return ((address[0] & 0xffL) << 24)
                | ((address[1] & 0xffL) << 16)
                | ((address[2] & 0xffL) << 8)
                | (address[3] & 0xffL);
    }

    private static boolean contains(long[][] ranges, long value) {
        for (long[] range : ranges) {
            if (value >= range[0] && value <= range[1]) return true;
        }
        return false;
    }
}
