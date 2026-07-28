package com.lucklotter.service;

import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.FlagDetailResponse;
import com.lucklotter.web.dto.FlagSummaryResponse;
import com.lucklotter.web.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

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

    public FlagQueryService(RetentionFlagRepository flags, OfferRepository offers) {
        this.flags = flags;
        this.offers = offers;
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
