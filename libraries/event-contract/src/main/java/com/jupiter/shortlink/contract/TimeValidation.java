package com.jupiter.shortlink.contract;

public final class TimeValidation {
    private TimeValidation() {}

    public static final String VERSION = "broker-time-v2";

    public static String validate(RawReceipt receipt, long occurredAt, long futureToleranceMillis) {
        if (!"LogAppendTime".equals(receipt.timestampType())
                && !"LOG_APPEND_TIME".equals(receipt.timestampType()))
            return "INVALID_TIMESTAMP_TYPE";
        if (receipt.receivedAt() <= 0 || occurredAt <= 0) return "INVALID_TIMESTAMP";
        if (occurredAt > receipt.receivedAt()
                && occurredAt - receipt.receivedAt() > futureToleranceMillis) return "FUTURE_EVENT";
        return "VALID";
    }

    public static boolean online(long occurredAt, long processingTime) {
        return occurredAt > 0
                && processingTime >= occurredAt
                && processingTime - occurredAt <= 480_000L;
    }
}
