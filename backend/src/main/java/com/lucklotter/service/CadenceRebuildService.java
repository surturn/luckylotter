package com.lucklotter.service;

import com.lucklotter.domain.Customer;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.PosTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Rebuilds every customer's denormalized cadence from their transaction history
 * (FR-2).
 *
 * <p>Ingestion recomputes a customer's cadence when they transact, which means a
 * change to <em>how</em> cadence is computed only reaches a customer on their
 * next visit. After the visit-collapse fix that left a specific, invisible
 * problem: a business that imported historical POS data keeps pre-fix averages,
 * and those averages feed the flag threshold directly. A customer whose visits
 * arrived as several receipts each reads as far more frequent than they are, so
 * their threshold collapses toward the floor and they are flagged for a win-back
 * offer after a few days away. Nothing about that is visible in the dashboard —
 * the numbers look plausible, they are just wrong.
 *
 * <p>Deliberately separate from the scan. Rewriting cadences is not something to
 * do on a schedule: it is a correction applied once after a change in how the
 * figure is derived, or after a bulk import, and the admin should be able to see
 * what it changed before the next scan acts on the new numbers.
 *
 * <p><strong>Does not touch flags.</strong> A rebuild can make a customer newly
 * flaggable or newly not, but resolving or withdrawing an existing flag on the
 * strength of a recomputed average would silently rewrite history the admin has
 * already seen and acted on. The next scan picks the new numbers up.
 */
@Service
public class CadenceRebuildService {

    private static final Logger log = LoggerFactory.getLogger(CadenceRebuildService.class);

    private final CustomerRepository customers;
    private final PosTransactionRepository transactions;

    public CadenceRebuildService(CustomerRepository customers,
                                 PosTransactionRepository transactions) {
        this.customers = customers;
        this.transactions = transactions;
    }

    /**
     * What one rebuild changed.
     *
     * @param examined          customers considered
     * @param cadenceChanged    customers whose average interval moved
     * @param becameFlaggable   customers who had no cadence and now have one
     * @param becameUnflaggable customers who had a cadence and no longer do —
     *                          the visit collapse takes rows away, so this is
     *                          the expected direction after that fix
     */
    public record RebuildSummary(UUID businessId,
                                 int examined,
                                 int cadenceChanged,
                                 int becameFlaggable,
                                 int becameUnflaggable) {
    }

    /**
     * Recomputes cadence, visit count and first/last visit for every customer of
     * one business from their transactions, which are the source of truth.
     *
     * <p>One transaction for the whole business, unlike the scan's
     * transaction-per-customer. The scan is isolated per customer so one failure
     * doesn't lose the whole run's work; here the opposite is wanted — a
     * half-applied rebuild would leave the population split between two
     * definitions of cadence, which is worse than not having run it.
     */
    @Transactional
    public RebuildSummary rebuild(UUID businessId) {
        List<Customer> population = customers.findByBusinessId(businessId);
        int cadenceChanged = 0;
        int becameFlaggable = 0;
        int becameUnflaggable = 0;

        for (Customer customer : population) {
            List<Instant> history = transactions.findVisitTimestamps(customer.getId());
            if (history.isEmpty()) {
                // No transactions to rebuild from. Leaving the record untouched
                // is right: zeroing it would invent a visit history the customer
                // doesn't have.
                continue;
            }

            BigDecimal previous = customer.getAvgIntervalDays();
            BigDecimal rebuilt = CadenceCalculator.averageIntervalDays(history);

            // Rows, not collapsed visits — matching what ingestion writes. Making
            // this the visit count here would give the column one meaning after a
            // rebuild and another after the customer's next sale. Flaggability
            // reads the cadence, not this, precisely because rows over-count.
            customer.setTransactionCount(history.size());
            customer.setAvgIntervalDays(rebuilt);
            // Rebuilt from the whole history rather than nudged forward. The
            // forward-only rule in ingestion guards against one backdated
            // arrival moving lastVisitAt backwards; here every row is in hand at
            // once, so the extremes are simply correct.
            customer.setFirstSeenAt(history.get(0));
            customer.setLastVisitAt(history.get(history.size() - 1));

            if (!Objects.equals(previous, rebuilt)) {
                cadenceChanged++;
                if (previous == null) {
                    becameFlaggable++;
                } else if (rebuilt == null) {
                    becameUnflaggable++;
                }
            }
        }

        RebuildSummary summary = new RebuildSummary(
                businessId, population.size(), cadenceChanged, becameFlaggable, becameUnflaggable);
        log.info("Cadence rebuild finished: businessId={} examined={} cadenceChanged={} "
                        + "becameFlaggable={} becameUnflaggable={}",
                businessId, summary.examined(), summary.cadenceChanged(),
                summary.becameFlaggable(), summary.becameUnflaggable());
        return summary;
    }
}
