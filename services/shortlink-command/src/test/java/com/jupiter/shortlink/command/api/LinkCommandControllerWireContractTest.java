package com.jupiter.shortlink.command.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;

class LinkCommandControllerWireContractTest {

    @Test
    void managementDatesUseTheAdminWireContract() {
        assertThat(
                        LinkCommandController.managementDateTime(
                                Timestamp.from(Instant.parse("2026-09-13T15:07:55.189Z"))))
                .isEqualTo("2026-09-13 23:07:55");
        assertThat(
                        LinkCommandController.managementDateTime(
                                LocalDateTime.of(2026, 9, 13, 15, 7, 55, 189_000_000)))
                .isEqualTo("2026-09-13 23:07:55");
        assertThat(LinkCommandController.managementDateTime(null)).isNull();
    }

    @Test
    void managementDatesDoNotDependOnTheJvmDefaultZone() {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"UTC", "Asia/Shanghai", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(
                                LinkCommandController.managementDateTime(
                                        Timestamp.from(Instant.parse("2026-09-14T18:30:00Z"))))
                        .isEqualTo("2026-09-15 02:30:00");
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
