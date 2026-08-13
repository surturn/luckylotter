package com.lucklotter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the offer cooldown window (FR-4) — how long a customer is left
 * alone after an offer has reached them.
 *
 * <p>The cooldown is what stops the retention loop closing on itself. A
 * resolved flag makes a customer immediately re-flaggable, so without it the
 * cycle "go quiet, collect a discount, come back, go quiet again" repeats
 * indefinitely, and the customers who like the business most are the ones best
 * placed to learn it.
 *
 * <p>Scaled to the customer's own rhythm for the same reason the flag threshold
 * is: a month of silence is a lapse for a weekly regular and unremarkable for
 * someone who visits twice a year.
 */
class BusinessCooldownTest {

    @Test
    @DisplayName("a frequent visitor's cooldown is held up by the floor")
    void frequentVisitorGetsTheFloor() {
        // 7-day cadence x 3.0 = 21 days, which is shorter than the 30-day floor.
        // Without the floor a weekly regular would be eligible again three weeks
        // after every offer, which is close enough to their own rhythm to be
        // worth cycling for.
        assertThat(business(30, "3.0").cooldownDaysFor(new BigDecimal("7.00")))
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("an occasional visitor's cooldown scales past the floor")
    void occasionalVisitorScalesUp() {
        // 60-day cadence x 3.0 = 180 days. A flat floor would let someone who
        // visits twice a year be re-flagged after one month, when a month of
        // silence is not a lapse for them at all.
        assertThat(business(30, "3.0").cooldownDaysFor(new BigDecimal("60.00")))
                .isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("a customer with no cadence falls back to the floor")
    void noCadenceFallsBackToFloor() {
        // Nothing to scale against. Reachable because the cooldown is checked
        // for any customer with a delivered offer, and a cadence can be absent.
        assertThat(business(30, "3.0").cooldownDaysFor(null))
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("the cooldown can be switched off entirely")
    void zeroDisablesTheCooldown() {
        // A business that wants no suppression at all must be able to say so —
        // the alternative is them working around it with a one-day floor.
        assertThat(business(0, "0").cooldownDaysFor(new BigDecimal("7.00")))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the longer of the two rules always wins")
    void takesTheLongerOfTheTwo() {
        Business generous = business(90, "1.0");
        // Floor wins at 7 days x 1.0 = 7; multiplier wins at 120 x 1.0 = 120.
        assertThat(generous.cooldownDaysFor(new BigDecimal("7.00")))
                .isEqualByComparingTo("90");
        assertThat(generous.cooldownDaysFor(new BigDecimal("120.00")))
                .isEqualByComparingTo("120.00");
    }

    private static Business business(int cooldownDays, String multiplier) {
        Business business = new Business();
        business.setOfferCooldownDays(cooldownDays);
        business.setOfferCooldownMultiplier(new BigDecimal(multiplier));
        return business;
    }
}
