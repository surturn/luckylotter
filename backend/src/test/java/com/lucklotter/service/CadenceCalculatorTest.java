package com.lucklotter.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the cadence calculation (FR-2) — the number every flag threshold
 * is derived from.
 *
 * <p>No database: the calculator is pure. The cases that matter are the ones
 * where the <em>transaction</em> count and the <em>visit</em> count disagree.
 * A POS export is not a list of visits — one trip to the counter can arrive as
 * five rows when a bill is split, when a customer pays for each item
 * separately, or when a till exports line items rather than sales. Because the
 * average is {@code span / (visits - 1)}, every extra row shrinks the learned
 * cadence without lengthening the span, which drags the flag threshold down
 * toward the lower clamp and makes the customer eligible for a win-back offer
 * far sooner than their real rhythm justifies.
 *
 * <p>That is both an accident that honest customers cause and the cheapest way
 * to game the trigger deliberately, which is why it is pinned here.
 */
class CadenceCalculatorTest {

    private static final Instant EPOCH = Instant.parse("2026-01-05T09:00:00Z");

    @Test
    @DisplayName("a weekly regular reads as a seven-day cadence")
    void weeklyRegular() {
        assertThat(CadenceCalculator.averageIntervalDays(weeklyVisits(11)))
                .isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("split receipts do not deflate the cadence")
    void splitReceiptsDoNotDeflateCadence() {
        // The same eleven weekly trips, but each one paid for in five
        // transactions a couple of minutes apart. The rhythm is identical; only
        // the row count changed.
        List<Instant> split = new ArrayList<>();
        for (Instant visit : weeklyVisits(11)) {
            for (int receipt = 0; receipt < 5; receipt++) {
                split.add(visit.plus(Duration.ofMinutes(2L * receipt)));
            }
        }

        assertThat(CadenceCalculator.averageIntervalDays(split))
                .as("55 rows describing 11 visits must still read as weekly")
                .isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("several receipts from one trip are not a cadence")
    void singleVisitPaidForInPartsHasNoCadence() {
        // Three rows, so the customer clears MIN_TRANSACTIONS on paper, but they
        // have been through the door exactly once. Returning a number here is
        // worse than returning nothing: the span is minutes, so the average
        // rounds to 0.00, which the customers.avg_interval_days > 0 check
        // constraint rejects — the ingest that wrote it fails.
        List<Instant> oneTrip = List.of(
                EPOCH,
                EPOCH.plus(Duration.ofMinutes(3)),
                EPOCH.plus(Duration.ofMinutes(7)));

        assertThat(CadenceCalculator.averageIntervalDays(oneTrip)).isNull();
    }

    @Test
    @DisplayName("two genuine trips in one day are still two visits")
    void distinctTripsOnTheSameDayBothCount() {
        // The guard against over-collapsing: a morning coffee and an evening
        // one are real, separate visits and must not be merged into a single
        // point. Three days, two visits each, nine hours apart.
        List<Instant> visits = List.of(
                EPOCH,
                EPOCH.plus(Duration.ofHours(9)),
                EPOCH.plus(Duration.ofDays(1)),
                EPOCH.plus(Duration.ofDays(1)).plus(Duration.ofHours(9)),
                EPOCH.plus(Duration.ofDays(2)),
                EPOCH.plus(Duration.ofDays(2)).plus(Duration.ofHours(9)));

        // Span is 2 days 9 hours across 6 visits: 2.375 / 5 = 0.475.
        assertThat(CadenceCalculator.averageIntervalDays(visits))
                .isEqualByComparingTo("0.48");
    }

    @Test
    @DisplayName("fewer than three visits has no cadence, however many rows")
    void belowMinimumVisitsHasNoCadence() {
        List<Instant> twoTripsManyReceipts = List.of(
                EPOCH,
                EPOCH.plus(Duration.ofMinutes(1)),
                EPOCH.plus(Duration.ofMinutes(4)),
                EPOCH.plus(Duration.ofDays(7)),
                EPOCH.plus(Duration.ofDays(7)).plus(Duration.ofMinutes(2)));

        assertThat(CadenceCalculator.averageIntervalDays(twoTripsManyReceipts)).isNull();
    }

    @Test
    @DisplayName("the answer does not depend on arrival order")
    void orderInsensitive() {
        // A backdated transaction arriving late must produce the same cadence as
        // if it had arrived in sequence, so nothing here may assume sorted input.
        List<Instant> shuffled = new ArrayList<>(weeklyVisits(11));
        Collections.shuffle(shuffled, new java.util.Random(20260729L));

        assertThat(CadenceCalculator.averageIntervalDays(shuffled))
                .isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("a cadence is never returned as zero")
    void neverReturnsZero() {
        // Whatever the input, the result either is null or satisfies the
        // avg_interval_days > 0 check constraint. Collapsing enforces a floor on
        // the gap between counted visits, so no burst of rows can round to zero.
        List<Instant> burst = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            burst.add(EPOCH.plus(Duration.ofSeconds(30L * i)));
        }

        BigDecimal cadence = CadenceCalculator.averageIntervalDays(burst);
        assertThat(cadence == null || cadence.signum() > 0)
                .as("cadence was %s, which the check constraint would reject", cadence)
                .isTrue();
    }

    /** {@code count} visits, exactly seven days apart, oldest first. */
    private static List<Instant> weeklyVisits(int count) {
        List<Instant> visits = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            visits.add(EPOCH.plus(Duration.ofDays(7L * i)));
        }
        return visits;
    }
}
