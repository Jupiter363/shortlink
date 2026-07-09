package com.nageoffer.shortlink.agent.riskprofile.model;

import java.time.LocalDateTime;

public record ShortLinkRiskSourceStats(
        String gid,
        String domain,
        String shortUri,
        String fullShortUrl,
        int pv2h,
        int uv2h,
        int pv24h,
        int uv24h,
        int pv7d,
        int uv7d,
        Double topIpShare,
        Double topVisitorShare,
        Double topRegionShare,
        Double topDeviceShare,
        Double topBrowserShare,
        Double peakHourShare,
        Double repeatVisitRatio,
        LocalDateTime profileWindowStart,
        LocalDateTime profileWindowEnd
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String gid = "";
        private String domain = "";
        private String shortUri = "";
        private String fullShortUrl = "";
        private int pv2h;
        private int uv2h;
        private int pv24h;
        private int uv24h;
        private int pv7d;
        private int uv7d;
        private Double topIpShare;
        private Double topVisitorShare;
        private Double topRegionShare;
        private Double topDeviceShare;
        private Double topBrowserShare;
        private Double peakHourShare;
        private Double repeatVisitRatio;
        private LocalDateTime profileWindowStart;
        private LocalDateTime profileWindowEnd;

        public Builder gid(String gid) {
            this.gid = valueOrEmpty(gid);
            return this;
        }

        public Builder domain(String domain) {
            this.domain = valueOrEmpty(domain);
            return this;
        }

        public Builder shortUri(String shortUri) {
            this.shortUri = valueOrEmpty(shortUri);
            return this;
        }

        public Builder fullShortUrl(String fullShortUrl) {
            this.fullShortUrl = valueOrEmpty(fullShortUrl);
            return this;
        }

        public Builder pv2h(int pv2h) {
            this.pv2h = nonNegative(pv2h);
            return this;
        }

        public Builder uv2h(int uv2h) {
            this.uv2h = nonNegative(uv2h);
            return this;
        }

        public Builder pv24h(int pv24h) {
            this.pv24h = nonNegative(pv24h);
            return this;
        }

        public Builder uv24h(int uv24h) {
            this.uv24h = nonNegative(uv24h);
            return this;
        }

        public Builder pv7d(int pv7d) {
            this.pv7d = nonNegative(pv7d);
            return this;
        }

        public Builder uv7d(int uv7d) {
            this.uv7d = nonNegative(uv7d);
            return this;
        }

        public Builder topIpShare(Double topIpShare) {
            this.topIpShare = ratioOrNull(topIpShare);
            return this;
        }

        public Builder topVisitorShare(Double topVisitorShare) {
            this.topVisitorShare = ratioOrNull(topVisitorShare);
            return this;
        }

        public Builder topRegionShare(Double topRegionShare) {
            this.topRegionShare = ratioOrNull(topRegionShare);
            return this;
        }

        public Builder topDeviceShare(Double topDeviceShare) {
            this.topDeviceShare = ratioOrNull(topDeviceShare);
            return this;
        }

        public Builder topBrowserShare(Double topBrowserShare) {
            this.topBrowserShare = ratioOrNull(topBrowserShare);
            return this;
        }

        public Builder peakHourShare(Double peakHourShare) {
            this.peakHourShare = ratioOrNull(peakHourShare);
            return this;
        }

        public Builder repeatVisitRatio(Double repeatVisitRatio) {
            this.repeatVisitRatio = ratioOrNull(repeatVisitRatio);
            return this;
        }

        public Builder profileWindowStart(LocalDateTime profileWindowStart) {
            this.profileWindowStart = profileWindowStart;
            return this;
        }

        public Builder profileWindowEnd(LocalDateTime profileWindowEnd) {
            this.profileWindowEnd = profileWindowEnd;
            return this;
        }

        public ShortLinkRiskSourceStats build() {
            return new ShortLinkRiskSourceStats(
                    gid,
                    domain,
                    shortUri,
                    fullShortUrl,
                    pv2h,
                    uv2h,
                    pv24h,
                    uv24h,
                    pv7d,
                    uv7d,
                    topIpShare,
                    topVisitorShare,
                    topRegionShare,
                    topDeviceShare,
                    topBrowserShare,
                    peakHourShare,
                    repeatVisitRatio,
                    profileWindowStart,
                    profileWindowEnd
            );
        }

        private String valueOrEmpty(String value) {
            return value == null ? "" : value;
        }

        private int nonNegative(int value) {
            return Math.max(0, value);
        }

        private Double ratioOrNull(Double value) {
            if (value == null) {
                return null;
            }
            if (value < 0D) {
                return 0D;
            }
            if (value > 1D) {
                return 1D;
            }
            return value;
        }
    }
}
