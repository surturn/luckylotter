package com.lucklotter.service;

import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.OverdueBand;
import com.lucklotter.domain.RetentionConstants;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.FlagActionRow;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.PosTransactionRepository.CustomerVisit;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.FlagDetailResponse;
import com.lucklotter.web.dto.FlagSummaryResponse;
import com.lucklotter.web.dto.FlagVisitsResponse;
import com.lucklotter.web.dto.FlagVisitsResponse.VisitPoint;
import com.lucklotter.web.dto.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read side of the admin dashboard (FR-7).
 *
 * <p>Every query takes {@code businessId} from the authenticated principal and
 * filters on it — including the single-flag lookup, which is why a flag
 * belonging to another business reads as "not found" rather than "forbidden"
 * (NFR-1).
 */
@Service
public class FlagQueryService {

    private final RetentionFlagRepository flags;
    private final OfferRepository offers;
    private final PosTransactionRepository transactions;

    public FlagQueryService(RetentionFlagRepository flags,
                            OfferRepository offers,
                            PosTransactionRepository transactions) {
        this.flags = flags;
        this.offers = offers;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public PageResponse<FlagSummaryResponse> list(UUID businessId, FlagStatus status,
                                                  FlagSort sort, int page, int size) {
        int offset = page * size;
        List<FlagActionRow> ranked = flags.flagActionPage(
                businessId, status == null ? null : status.name(), sort.name(), size, offset);
        long total = status == null
                ? flags.countByBusinessId(businessId)
                : flags.countByBusinessIdAndStatus(businessId, status);

        if (ranked.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, total, pages(total, size));
        }

        // The entities for the page, fetched by the IDs the ranking chose. The
        // order is the ranking's, so the map has to be re-sorted by it — an IN
        // clause makes no promise about the order it returns rows in.
        Map<UUID, FlagActionRow> byFlagId = ranked.stream()
                .collect(Collectors.toMap(FlagActionRow::getFlagId, row -> row));
        Map<UUID, RetentionFlag> entities = flags
                .findByIdsWithCustomerAndOffer(byFlagId.keySet()).stream()
                .collect(Collectors.toMap(RetentionFlag::getId, flag -> flag));

        List<FlagSummaryResponse> items = ranked.stream()
                .map(row -> entities.get(row.getFlagId()))
                .filter(Objects::nonNull)
                .map(flag -> toSummary(flag, byFlagId.get(flag.getId())))
                .toList();
        return new PageResponse<>(items, page, size, total, pages(total, size));
    }

    private static int pages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }

    @Transactional(readOnly = true)
    public FlagDetailResponse detail(UUID flagId, UUID businessId) {
        RetentionFlag flag = flags.findByIdAndBusinessId(flagId, businessId)
                .orElseThrow(() -> new NotFoundException("Flag not found"));
        return toDetail(flag);
    }

    /**
     * One flagged customer's recent visits, for the rhythm chart (FR-7).
     *
     * <p>Scoped through the flag, so a caller can only read visit history for a
     * flag their own business owns (NFR-1).
     */
    @Transactional(readOnly = true)
    public FlagVisitsResponse visits(UUID flagId, UUID businessId) {
        RetentionFlag flag = flags.findByIdAndBusinessId(flagId, businessId)
                .orElseThrow(() -> new NotFoundException("Flag not found"));
        return new FlagVisitsResponse(
                flag.getId(),
                flag.getFlaggedAt(),
                trimmedVisits(transactions.findVisitsForCustomers(List.of(flag.getCustomer().getId()))
                        .stream()
                        .map(CustomerVisit::getOccurredAt)
                        .toList()));
    }

    /**
     * Visit history for a page of flags in one round trip (FR-7, NFR-5).
     *
     * <p>The alternative — the table calling the single-flag endpoint per row —
     * is an N+1 over HTTP, which is the same mistake the fetch-joined dashboard
     * query exists to avoid, just moved up a layer.
     */
    @Transactional(readOnly = true)
    public List<FlagVisitsResponse> visitsForFlags(Collection<UUID> flagIds, UUID businessId) {
        if (flagIds.isEmpty()) {
            return List.of();
        }
        List<RetentionFlag> owned = flags.findAllById(flagIds).stream()
                // Filtering here, not in the query, keeps the tenant check in
                // one obvious place; unowned IDs simply vanish from the result.
                .filter(flag -> flag.getBusiness().getId().equals(businessId))
                .toList();
        if (owned.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<Instant>> byCustomer = transactions
                .findVisitsForCustomers(owned.stream().map(f -> f.getCustomer().getId()).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        CustomerVisit::getCustomerId,
                        Collectors.mapping(CustomerVisit::getOccurredAt, Collectors.toList())));

        return owned.stream()
                .map(flag -> new FlagVisitsResponse(
                        flag.getId(),
                        flag.getFlaggedAt(),
                        trimmedVisits(byCustomer.getOrDefault(flag.getCustomer().getId(), List.of()))))
                .toList();
    }

    /**
     * Newest {@link RetentionConstants#VISIT_HISTORY_LIMIT} visits, returned
     * oldest first — the order a chart draws them in.
     *
     * <p>Collapsed into visits by {@link CadenceCalculator#distinctVisits} first,
     * for the same reason the cadence is: a split bill is one trip, and drawing
     * it as three points would show the admin a rhythm the customer does not
     * have — on the same screen as a cadence figure that counted it once.
     * Collapsing before the limit matters, or twelve rows from one busy
     * afternoon would fill a chart meant to span months.
     */
    private static List<VisitPoint> trimmedVisits(List<Instant> transactionTimestamps) {
        List<Instant> visits = CadenceCalculator.distinctVisits(transactionTimestamps);
        return visits.subList(Math.max(0, visits.size() - RetentionConstants.VISIT_HISTORY_LIMIT),
                        visits.size())
                .stream()
                .map(VisitPoint::new)
                .toList();
    }

    /**
     * How many of this business's flagged customers can't be reached (FR-5).
     * Surfacing the number is the point of the {@code NO_CONTACT} status — it
     * measures how much of their POS data lacks contact details.
     */
    @Transactional(readOnly = true)
    public long countUncontactable(UUID businessId) {
        return offers.countByBusinessIdAndStatus(businessId, OfferStatus.NO_CONTACT);
    }

    private static FlagSummaryResponse toSummary(RetentionFlag flag, FlagActionRow action) {
        Customer customer = flag.getCustomer();
        Offer offer = flag.getOffer();
        return new FlagSummaryResponse(
                flag.getId(),
                customer.getId(),
                customer.getExternalRef(),
                customer.getName(),
                flag.getStatus(),
                customer.getLastVisitAt(),
                flag.getFlaggedAt(),
                flag.getAvgIntervalDaysAtFlag(),
                flag.getThresholdDaysApplied(),
                customer.isContactable(),
                offer == null ? null : offer.getDealType(),
                offer == null ? null : offer.getDealValue(),
                offer == null ? null : offer.getStatus(),
                offer == null ? null : offer.getRedemptionCode(),
                offer == null ? null : offer.getSentAt(),
                action.getVisitCount(),
                action.getTotalSpend(),
                action.getAverageTransactionValue(),
                customer.getUsualItem(),
                expectedNextVisitAt(flag),
                action.getDaysOverdue(),
                OverdueBand.of(action.getOverdueness()),
                action.getPriorInterventions(),
                action.getPriorReturns(),
                action.getPriorityScore(),
                new FlagSummaryResponse.Priority(
                        action.getRiskPoints(), action.getValuePoints(), action.getHistoryPoints()),
                recommendedAction(flag, offer));
    }

    /**
     * When the rhythm the flag was raised on said this customer was due.
     *
     * <p>From the last visit and the cadence snapshotted at flagging, not from
     * the customer's cadence now — the whole point of the detail view is to show
     * the reasoning the flag was built from, and a live read would silently
     * change the arithmetic under a flag raised weeks ago (§13).
     */
    private static Instant expectedNextVisitAt(RetentionFlag flag) {
        Instant lastVisit = flag.getCustomer().getLastVisitAt();
        if (lastVisit == null || flag.getAvgIntervalDaysAtFlag() == null) {
            return null;
        }
        long seconds = flag.getAvgIntervalDaysAtFlag()
                .multiply(BigDecimal.valueOf(86400))
                .longValue();
        return lastVisit.plusSeconds(seconds);
    }

    /**
     * What to do about one row, from the state it is actually in (§5).
     *
     * <p>Each branch corresponds to a recorded fact rather than to advice: no
     * contact details in the POS export, a send that failed and will be retried,
     * a budget already spent, an offer delivered and unanswered. The screen
     * shows this so an operator doesn't have to read the status columns and work
     * out the consequence themselves.
     */
    private static String recommendedAction(RetentionFlag flag, Offer offer) {
        if (flag.getStatus() == FlagStatus.RESOLVED) {
            return "Won back";
        }
        if (offer == null) {
            return flag.getCustomer().isContactable()
                    ? "No offer yet — run the scan"
                    : "No way to reach them — add an email or phone";
        }
        return switch (offer.getStatus()) {
            case NO_CONTACT -> "No way to reach them — add an email or phone";
            case SUPPRESSED_BUDGET -> "Budget reached — nothing sent";
            case FAILED -> "Delivery failed — will retry";
            case PENDING -> "Queued to send";
            case SENT -> "Offer sent — awaiting their visit";
        };
    }

    private static FlagDetailResponse toDetail(RetentionFlag flag) {
        Customer customer = flag.getCustomer();
        Offer offer = flag.getOffer();
        return new FlagDetailResponse(
                flag.getId(),
                flag.getStatus(),
                flag.getFlaggedAt(),
                flag.getResolvedAt(),
                customer.getId(),
                customer.getExternalRef(),
                customer.getName(),
                customer.getFirstSeenAt(),
                customer.getLastVisitAt(),
                customer.getTransactionCount(),
                customer.getAvgIntervalDays(),
                customer.isContactable(),
                flag.getAvgIntervalDaysAtFlag(),
                flag.getThresholdDaysApplied(),
                offer == null ? null : offer.getId(),
                offer == null ? null : offer.getDealType(),
                offer == null ? null : offer.getDealValue(),
                offer == null ? null : offer.getStatus(),
                offer == null ? null : offer.getRedemptionCode(),
                offer == null ? null : offer.getFailureCode(),
                offer == null ? null : offer.getSentAt());
    }
}
