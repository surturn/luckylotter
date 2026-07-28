package com.lucklotter.service;

import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.RetentionConstants;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The cadence-break scan (FR-3): finds customers who have gone quiet relative to
 * their own rhythm, flags them, and generates their offers.
 *
 * <p>Safe to re-run and safe to overlap — at most one open flag per customer is
 * enforced by the database, not by this loop (FR-8, NFR-3).
 */
@Service
public class RetentionScanService {

    private static final Logger log = LoggerFactory.getLogger(RetentionScanService.class);
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400L);

    private final BusinessRepository businesses;
    private final CustomerRepository customers;
    private final FlagCreationService flagCreation;
    private final OfferDispatchService offerDispatch;

    public RetentionScanService(BusinessRepository businesses,
                                CustomerRepository customers,
                                FlagCreationService flagCreation,
                                OfferDispatchService offerDispatch) {
        this.businesses = businesses;
        this.customers = customers;
        this.flagCreation = flagCreation;
        this.offerDispatch = offerDispatch;
    }

    /** What one business's scan did. Logged, and returned by the manual trigger. */
    public record ScanSummary(UUID businessId, int scanned, int flagged, int skipped, int errored) {
    }

    /** Daily sweep across every business (FR-3). */
    @Scheduled(cron = "${lucklotter.retention.scan-cron}")
    public void scheduledScan() {
        String runId = UUID.randomUUID().toString();
        MDC.put("runId", runId);
        try {
            List<Business> all = businesses.findAll();
            log.info("Retention scan starting: businesses={}", all.size());
            int totalFlagged = 0;
            for (Business business : all) {
                totalFlagged += scan(business.getId()).flagged();
            }
            log.info("Retention scan finished: businesses={} flagged={}", all.size(), totalFlagged);
        } finally {
            MDC.remove("runId");
        }
    }

    /**
     * Scans one business. Deliberately not {@code @Transactional}: each customer
     * is flagged in its own transaction so one failure can't discard the rest of
     * the run.
     */
    public ScanSummary scan(UUID businessId) {
        Business business = businesses.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));
        Instant now = Instant.now();

        // Coarse pre-filter: nobody can be flagged before the lower clamp
        // elapses, whatever their cadence, so the scan stays on the
        // (business_id, last_visit_at) index instead of loading everyone (NFR-5).
        Instant cutoff = now.minus(business.getMinThresholdDays(), ChronoUnit.DAYS);
        List<Customer> candidates = customers.findFlagCandidates(
                businessId, RetentionConstants.MIN_TRANSACTIONS, cutoff);

        int flagged = 0;
        int skipped = 0;
        int errored = 0;
        for (Customer candidate : candidates) {
            try {
                BigDecimal threshold = business.thresholdDaysFor(candidate.getAvgIntervalDays());
                BigDecimal quietDays = daysBetween(candidate.getLastVisitAt(), now);
                // The SQL pre-filter is not the decision — the clamped
                // per-customer threshold is (FR-3, FR-6).
                if (quietDays.compareTo(threshold) <= 0) {
                    skipped++;
                    continue;
                }
                boolean created = flagCreation
                        .flagAndGenerateOffer(candidate.getId(), businessId, threshold)
                        .isPresent();
                if (created) {
                    flagged++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                // One bad customer must not end the sweep.
                errored++;
                log.error("Flagging failed: businessId={} customerId={}",
                        businessId, candidate.getId(), e);
            }
        }

        ScanSummary summary = new ScanSummary(businessId, candidates.size(), flagged, skipped, errored);
        log.info("Scan summary: businessId={} scanned={} flagged={} skipped={} errored={}",
                businessId, summary.scanned(), summary.flagged(), summary.skipped(), summary.errored());

        // Sending is outside the flagging transactions on purpose: a delivery
        // failure records itself on the offer and must not undo the flag (FR-5).
        offerDispatch.dispatchSendable(businessId);
        return summary;
    }

    private static BigDecimal daysBetween(Instant from, Instant to) {
        return BigDecimal.valueOf(Duration.between(from, to).getSeconds())
                .divide(SECONDS_PER_DAY, 2, RoundingMode.HALF_UP);
    }
}
