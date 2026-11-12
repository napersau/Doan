package com.vetclinic.clinical.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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

    // ================================================================== Đẩy lùi 1 bậc (Q4)

    private static QueueOrders.Entry reg(long q) {
        return new QueueOrders.Entry(q, false);
    }

    private static QueueOrders.Entry emergency(long q) {
        return new QueueOrders.Entry(q, true);
    }

    @Test
    void pushBack_withTwoOrMorePeopleBehind_isTheAverageOfTheNextTwo() {
        // A bị lùi: xếp ngay sau B (200) và trước C (300) => (200 + 300) / 2
        assertThat(QueueOrders.afterPushBack(List.of(reg(100), reg(200), reg(300)), 0)).isEqualTo(250L);
        // Không nhất thiết là đầu hàng: B (index 1) bị lùi => giữa C (300) và D (400)
        assertThat(QueueOrders.afterPushBack(List.of(reg(100), reg(200), reg(300), reg(400)), 1)).isEqualTo(350L);
    }

    @Test
    void pushBack_withOnlyOnePersonBehind_isThatPersonPlusOne() {
        assertThat(QueueOrders.afterPushBack(List.of(reg(100), reg(200)), 0)).isEqualTo(201L);
    }

    @Test
    void pushBack_withNobodyBehind_keepsThePosition() {
        assertThat(QueueOrders.afterPushBack(List.of(reg(100)), 0)).isNull();
        assertThat(QueueOrders.afterPushBack(List.of(reg(100), reg(200)), 1)).isNull();
    }

    @Test
    void pushBack_whenTheNextIsAnUnskippedEmergency_goesToTheHeadOfTheRegularTier() {
        // X và Y đều là cấp cứu chưa bị lùi; R là ca thường đầu tiên (300). X mất ưu tiên => đứng ngay trước R (299),
        // vẫn sau mọi ca cấp cứu vì ca cấp cứu luôn xếp trước ca thường.
        assertThat(QueueOrders.afterPushBack(List.of(emergency(100), emergency(200), reg(300), reg(400)), 0)).isEqualTo(299L);
    }

    @Test
    void pushBack_ofAnEmergencyWithOnlyRegularCasesBehind_usesTheRegularFormula() {
        // X cấp cứu, người kế tiếp là ca thường: X bị lùi thành ca thường, xếp giữa 200 và 300
        assertThat(QueueOrders.afterPushBack(List.of(emergency(100), reg(200), reg(300)), 0)).isEqualTo(250L);
    }

    @Test
    void pushBack_ofAnEmergencyBehindOnlyOtherEmergencies_changesNothingButTheTier() {
        // Không có ca thường nào: mất ưu tiên là đã tự xếp sau Y, queue_order giữ nguyên
        assertThat(QueueOrders.afterPushBack(List.of(emergency(100), emergency(200)), 0)).isEqualTo(100L);
    }

    @Test
    void now_usesTheGivenClock() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T02:00:00Z"), ZoneOffset.UTC);
        assertThat(QueueOrders.now(clock)).isEqualTo(1_793_325_600_000_000L);
    }
}
