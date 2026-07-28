package com.lucklotter.service;

import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.RetentionConstants;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.PosTransactionRepository.CustomerVisit;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.FlagDetailResponse;
import com.lucklotter.web.dto.FlagSummaryResponse;
import com.lucklotter.web.dto.FlagVisitsResponse;
import com.lucklotter.web.dto.FlagVisitsResponse.VisitPoint;
import com.lucklotter.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
    public PageResponse<FlagSummaryResponse> list(UUID businessId, FlagStatus status, int page, int size) {
        // Newest first: an admin opening the dashboard wants today's flags, not
        // the oldest ones ever recorded.
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "flaggedAt"));
        Page<RetentionFlag> found = status == null
                ? flags.findForDashboard(businessId, pageable)
                : flags.findForDashboardByStatus(businessId, status, pageable);
        return PageResponse.of(found.map(FlagQueryService::toSummary));
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
     */
    private static List<VisitPoint> trimmedVisits(List<Instant> newestFirst) {
        return newestFirst.stream()
                .limit(RetentionConstants.VISIT_HISTORY_LIMIT)
                .sorted()
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

    private static FlagSummaryResponse toSummary(RetentionFlag flag) {
        Customer customer = flag.getCustomer();
        Offer offer = flag.getOffer();
        return new FlagSummaryResponse(
                flag.getId(),
                customer.getId(),
                customer.getExternalRef(),
                flag.getStatus(),
                customer.getLastVisitAt(),
                flag.getFlaggedAt(),
                flag.getAvgIntervalDaysAtFlag(),
                flag.getThresholdDaysApplied(),
                customer.isContactable(),
                offer == null ? null : offer.getDealType(),
                offer == null ? null : offer.getDealValue(),
                offer == null ? null : offer.getStatus(),
                offer == null ? null : offer.getSentAt());
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
                offer == null ? null : offer.getFailureCode(),
                offer == null ? null : offer.getSentAt());
    }
}
