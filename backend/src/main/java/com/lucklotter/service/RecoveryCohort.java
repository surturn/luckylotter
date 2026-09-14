package com.lucklotter.service;

import com.lucklotter.repo.RetentionImpactRow;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A recovery rate together with what it was computed from.
 *
 * <p>Exists so the rate is derived in exactly one place. The overview and the
 * retention-impact section both show a recovery percentage, and the failure this
 * guards against is the one the numbers on a retention dashboard are most prone
 * to: two cards whose percentages were computed from subtly different filters,
 * agreeing only until somebody checks them against each other.
 *
 * @param contacted   offers that reached the customer
 * @param returned    of those, how many bought again inside the window
 * @param windowDays  the window each return had to fall inside
 * @param percent     null when nothing has been contacted yet — no rate, which is
 *                    not the same statement as a rate of 0%
 */
public record RecoveryCohort(long contacted, long returned, int windowDays, BigDecimal percent) {

    public static RecoveryCohort of(RetentionImpactRow raw, int windowDays) {
        return new RecoveryCohort(raw.getContacted(), raw.getReturned(), windowDays,
                raw.getContacted() == 0 ? null
                        : BigDecimal.valueOf(raw.getReturned())
                                .multiply(BigDecimal.valueOf(100))
                                .divide(BigDecimal.valueOf(raw.getContacted()), 1, RoundingMode.HALF_UP));
    }
}
