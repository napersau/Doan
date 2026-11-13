package com.vetclinic.clinical.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class QueueOrdersTest {

    @Test
    void of_isEpochMicroseconds() {
        // 2026-10-30T02:00:00Z = 1.793.325.600 giây kể từ epoch
        assertThat(QueueOrders.of(Instant.parse("2026-10-30T02:00:00Z"))).isEqualTo(1_793_325_600_000_000L);
        assertThat(QueueOrders.of(Instant.parse("2026-10-30T02:00:00.123456Z"))).isEqualTo(1_793_325_600_123_456L);
    }

    @Test
    void of_dropsNanosecondsBelowOneMicrosecond() {
        assertThat(QueueOrders.of(Instant.parse("2026-10-30T02:00:00.000001999Z"))).isEqualTo(1_793_325_600_000_001L);
    }

    @Test
    void later_instants_getLargerValues_soEarlierArrivalsAreCalledFirst() {
        long first = QueueOrders.of(Instant.parse("2026-10-30T02:00:00.000001Z"));
        long second = QueueOrders.of(Instant.parse("2026-10-30T02:00:00.000002Z"));
        assertThat(first).isLessThan(second);
    }
}
