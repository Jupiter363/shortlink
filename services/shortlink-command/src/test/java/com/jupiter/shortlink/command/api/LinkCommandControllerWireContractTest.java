package com.jupiter.shortlink.command.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;

class LinkCommandControllerWireContractTest {

    @Test
    void managementDatesUseTheAdminWireContract() {
        assertThat(
                        LinkCommandController.managementDateTime(
                                Timestamp.valueOf("2026-09-13 15:07:55.189")))
                .isEqualTo("2026-09-13 15:07:55");
        assertThat(
                        LinkCommandController.managementDateTime(
                                LocalDateTime.of(2026, 9, 13, 15, 7, 55, 189_000_000)))
                .isEqualTo("2026-09-13 15:07:55");
        assertThat(LinkCommandController.managementDateTime(null)).isNull();
    }
}
