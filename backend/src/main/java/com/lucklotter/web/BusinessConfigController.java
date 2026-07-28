package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.BusinessConfigService;
import com.lucklotter.web.dto.BusinessConfigResponse;
import com.lucklotter.web.dto.BusinessConfigUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trigger tuning and deal defaults (FR-6, §10).
 *
 * <p>The path says {@code /me} rather than {@code /{businessId}} deliberately:
 * with no business ID in the URL there is nothing for a caller to tamper with,
 * and the tenant scope can only come from the token (NFR-1).
 */
@RestController
@RequestMapping("/v1/businesses/me/config")
public class BusinessConfigController {

    private final BusinessConfigService businessConfigService;

    public BusinessConfigController(BusinessConfigService businessConfigService) {
        this.businessConfigService = businessConfigService;
    }

    @GetMapping
    public BusinessConfigResponse get(@AuthenticationPrincipal AdminPrincipal admin) {
        return businessConfigService.get(admin.businessId());
    }

    @PutMapping
    public BusinessConfigResponse update(@AuthenticationPrincipal AdminPrincipal admin,
                                         @Valid @RequestBody BusinessConfigUpdateRequest request) {
        return businessConfigService.update(admin.businessId(), request);
    }
}
