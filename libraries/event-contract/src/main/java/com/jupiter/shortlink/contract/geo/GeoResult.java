package com.jupiter.shortlink.contract.geo;

/** Country is ISO alpha-2; network is the database's ISP label, never Wi-Fi or mobile access. */
public record GeoResult(String country, String province, String city, String network,
                        String status, String version) {
    public GeoResult {
        country = knownOrUnknown(country);
        province = knownOrUnknown(province);
        city = knownOrUnknown(city);
        network = knownOrUnknown(network);
        status = status == null || status.isBlank() ? "UNKNOWN" : status;
        version = version == null ? "" : version;
    }

    public static GeoResult unknown(String status, String version) {
        return new GeoResult("UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", status, version);
    }

    private static String knownOrUnknown(String value) {
        return value == null || value.isBlank() || "0".equals(value) ? "UNKNOWN" : value;
    }
}
