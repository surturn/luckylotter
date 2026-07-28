package com.lucklotter.service;

import com.lucklotter.domain.Business;
import com.lucklotter.domain.RetentionConstants;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.web.dto.BusinessConfigResponse;
import com.lucklotter.web.dto.BusinessConfigUpdateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Trigger tuning and deal defaults for one business (FR-6, US-1, US-3). */
@Service
public class BusinessConfigService {

    private static final Logger log = LoggerFactory.getLogger(BusinessConfigService.class);

    private final BusinessRepository businesses;

    public BusinessConfigService(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    @Transactional(readOnly = true)
    public BusinessConfigResponse get(UUID businessId) {
        return toResponse(load(businessId));
    }

    /**
     * @param businessId the caller's own business, from the JWT (NFR-1) — there
     *                   is deliberately no endpoint for editing another one
     */
    @Transactional
    public BusinessConfigResponse update(UUID businessId, BusinessConfigUpdateRequest request) {
        // Bean validation can bound each field but not relate two of them, so
        // the cross-field rule lives here (FR-6).
        if (request.minThresholdDays() > request.maxThresholdDays()) {
            throw new ValidationException(
                    "minThresholdDays must be less than or equal to maxThresholdDays");
        }

        Business business = load(businessId);
        business.setSensitivityMultiplier(request.sensitivityMultiplier());
        business.setMinThresholdDays(request.minThresholdDays());
        business.setMaxThresholdDays(request.maxThresholdDays());
        business.setDefaultDealType(request.defaultDealType());
        business.setDefaultDealValue(request.defaultDealValue());

        log.info("Business config updated: businessId={} multiplier={} clamp=[{},{}] deal={}/{}",
                businessId, request.sensitivityMultiplier(), request.minThresholdDays(),
                request.maxThresholdDays(), request.defaultDealType(), request.defaultDealValue());
        return toResponse(business);
    }

    private Business load(UUID businessId) {
        return businesses.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));
    }

    private BusinessConfigResponse toResponse(Business business) {
        return new BusinessConfigResponse(
                business.getName(),
                business.getSensitivityMultiplier(),
                business.getMinThresholdDays(),
                business.getMaxThresholdDays(),
                business.getDefaultDealType(),
                business.getDefaultDealValue(),
                RetentionConstants.MIN_TRANSACTIONS);
    }
}
