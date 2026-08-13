package com.lucklotter.service;

import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.PosTransaction;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.TransactionIngestRequest;
import com.lucklotter.web.dto.TransactionIngestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * POS transaction ingestion (FR-1), cadence maintenance (FR-2), and flag
 * auto-resolution (FR-9) — one transaction, so a customer's counters can never
 * disagree with the rows they were derived from.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final BusinessRepository businesses;
    private final CustomerRepository customers;
    private final PosTransactionRepository transactions;
    private final RetentionFlagRepository flags;

    public IngestionService(BusinessRepository businesses,
                            CustomerRepository customers,
                            PosTransactionRepository transactions,
                            RetentionFlagRepository flags) {
        this.businesses = businesses;
        this.customers = customers;
        this.transactions = transactions;
        this.flags = flags;
    }

    /**
     * @param businessId the caller's business, from the JWT — never from the
     *                   request body (NFR-1)
     */
    @Transactional
    public TransactionIngestResponse ingest(UUID businessId, TransactionIngestRequest request) {
        Business business = businesses.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        // NFR-3: replaying a POS transaction is a no-op, not a second visit.
        var duplicate = transactions.findByBusinessIdAndExternalTxnId(businessId, request.externalTxnId());
        if (duplicate.isPresent()) {
            PosTransaction existing = duplicate.get();
            log.info("Ingest skipped as duplicate: businessId={} transactionId={} customerId={}",
                    businessId, existing.getId(), existing.getCustomer().getId());
            return new TransactionIngestResponse(
                    existing.getId(), existing.getCustomer().getId(), true, null);
        }

        Customer customer = resolveCustomer(business, request);
        applyContactDetails(customer, request);

        PosTransaction transaction = new PosTransaction();
        transaction.setBusiness(business);
        transaction.setCustomer(customer);
        transaction.setExternalTxnId(request.externalTxnId());
        transaction.setAmount(request.amount());
        transaction.setOccurredAt(request.occurredAt());
        transactions.saveAndFlush(transaction);

        // Is this the customer coming back, or history arriving late? Captured
        // before recomputeCadence, which is what moves lastVisitAt forward.
        boolean isReturnVisit = customer.getLastVisitAt() == null
                || request.occurredAt().isAfter(customer.getLastVisitAt());

        recomputeCadence(customer, request.occurredAt());
        UUID resolvedFlagId = isReturnVisit ? resolveOpenFlag(customer) : null;

        log.info("Ingested transaction: businessId={} customerId={} transactionId={} "
                        + "transactionCount={} avgIntervalDays={} resolvedFlagId={}",
                businessId, customer.getId(), transaction.getId(),
                customer.getTransactionCount(), customer.getAvgIntervalDays(), resolvedFlagId);

        return new TransactionIngestResponse(
                transaction.getId(), customer.getId(), false, resolvedFlagId);
    }

    /** First sighting creates the customer (FR-1). */
    private Customer resolveCustomer(Business business, TransactionIngestRequest request) {
        return customers.findByBusinessIdAndExternalRef(business.getId(), request.customerRef())
                .orElseGet(() -> {
                    Customer created = new Customer();
                    created.setBusiness(business);
                    created.setExternalRef(request.customerRef());
                    created.setFirstSeenAt(request.occurredAt());
                    return customers.save(created);
                });
    }

    /**
     * Contact details are filled in, never cleared: a POS export that omits the
     * email on a later sale must not erase one an earlier sale supplied (FR-1).
     *
     * <p>The display name follows the same rule for the same reason.
     */
    private void applyContactDetails(Customer customer, TransactionIngestRequest request) {
        if (request.customerName() != null && !request.customerName().isBlank()) {
            customer.setName(request.customerName().trim());
        }
        if (request.usualItem() != null && !request.usualItem().isBlank()) {
            customer.setUsualItem(request.usualItem().trim());
        }
        if (request.contactEmail() != null && !request.contactEmail().isBlank()) {
            customer.setContactEmail(request.contactEmail().trim());
        }
        if (request.contactPhone() != null && !request.contactPhone().isBlank()) {
            customer.setContactPhone(request.contactPhone().trim());
        }
    }

    /**
     * Recomputes the denormalized cadence state from the customer's full visit
     * history (FR-2).
     *
     * <p>{@code lastVisitAt} only ever moves forward: ingesting a backdated
     * transaction must not make a quiet customer look recently active, which
     * would silently un-flag them.
     */
    private void recomputeCadence(Customer customer, Instant occurredAt) {
        List<Instant> visits = transactions.findVisitTimestamps(customer.getId());
        customer.setTransactionCount(visits.size());
        BigDecimal cadence = CadenceCalculator.averageIntervalDays(visits);
        customer.setAvgIntervalDays(cadence);
        if (customer.getLastVisitAt() == null || occurredAt.isAfter(customer.getLastVisitAt())) {
            customer.setLastVisitAt(occurredAt);
        }
        if (customer.getFirstSeenAt() == null || occurredAt.isBefore(customer.getFirstSeenAt())) {
            customer.setFirstSeenAt(occurredAt);
        }
    }

    /**
     * The customer visited, so their open flag is answered (FR-9). Exactly one
     * can be open — the partial unique index guarantees it.
     *
     * <p>Only called for a transaction that moves {@code lastVisitAt} forward.
     * A <strong>backdated</strong> one must not resolve a flag: importing a
     * year of POS history for a customer who is currently lapsed would close
     * their flag on the strength of a sale from months ago, reporting them as
     * recovered when nobody has seen them. Worse, it would do it silently and
     * in bulk — one import could clear a whole dashboard. The same condition
     * governs both, so a transaction that cannot move the last-visit date
     * cannot answer the flag either.
     *
     * @return the flag that was closed, or null if none was open
     */
    private UUID resolveOpenFlag(Customer customer) {
        RetentionFlag open = flags
                .findByCustomerIdAndStatus(customer.getId(), FlagStatus.ACTIVE)
                .orElse(null);
        if (open == null) {
            return null;
        }
        open.resolve(Instant.now());
        return open.getId();
    }
}
