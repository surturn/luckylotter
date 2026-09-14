package com.lucklotter.repo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one flagged customer is worth acting on, computed in the database.
 *
 * <p>Interface projection rather than a record: Spring Data can't construct a
 * record from a native query without a {@code @SqlResultSetMapping}, and this
 * one is native because the priority ordering needs window functions and
 * {@code FILTER} clauses JPQL doesn't have.
 *
 * <p>This is the query behind the flag list's default order. Ranking happens in
 * SQL, not in the service, because the list is paged: sorting the page in Java
 * would order twenty-five rows out of eight hundred and call the result "the
 * most urgent customers".
 */
public interface FlagActionRow {

    UUID getFlagId();

    /**
     * Quiet days divided by the threshold this flag crossed. 1.0 is the moment
     * of flagging; null for a resolved flag, where the question no longer
     * applies.
     */
    BigDecimal getOverdueness();

    /** Quiet days past the threshold. Null for a resolved flag. */
    BigDecimal getDaysOverdue();

    /** Everything this customer has ever spent (FR-7). */
    BigDecimal getTotalSpend();

    BigDecimal getAverageTransactionValue();

    /** Purchases on record, not visits — a split bill counts more than once. */
    Integer getVisitCount();

    /** Offers delivered to this customer before this flag's own. */
    Long getPriorInterventions();

    /** How many of those were followed by a purchase inside the recovery window. */
    Long getPriorReturns();

    /** 0-100, the three components below combined. */
    Integer getPriorityScore();

    /** How far past their rhythm, 0-100. */
    Integer getRiskPoints();

    /** What they are worth against the business's own average customer, 0-100. */
    Integer getValuePoints();

    /** How they responded to previous offers, 0-100. */
    Integer getHistoryPoints();
}
