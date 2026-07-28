package com.lucklotter.web;

import com.lucklotter.domain.FlagStatus;
import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.FlagQueryService;
import com.lucklotter.web.dto.FlagDetailResponse;
import com.lucklotter.web.dto.FlagSummaryResponse;
import com.lucklotter.web.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Flagged-customer views for the admin dashboard (FR-7, §10). */
@RestController
@RequestMapping("/v1/flags")
@Validated
public class FlagController {

    private final FlagQueryService flagQueryService;

    public FlagController(FlagQueryService flagQueryService) {
        this.flagQueryService = flagQueryService;
    }

    /**
     * @param status optional filter; omit for every flag regardless of state
     * @param size   capped, so a caller can't ask for the whole table in one
     *               page and blow the dashboard's latency budget (NFR-5)
     */
    @GetMapping
    public PageResponse<FlagSummaryResponse> list(
            @AuthenticationPrincipal AdminPrincipal admin,
            @RequestParam(required = false) FlagStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return flagQueryService.list(admin.businessId(), status, page, size);
    }

    /**
     * Coverage counter for the dashboard (FR-5): how many flagged customers the
     * business has no way to contact.
     */
    @GetMapping("/stats")
    public Map<String, Long> stats(@AuthenticationPrincipal AdminPrincipal admin) {
        return Map.of("uncontactableOffers", flagQueryService.countUncontactable(admin.businessId()));
    }

    @GetMapping("/{id}")
    public FlagDetailResponse detail(@AuthenticationPrincipal AdminPrincipal admin,
                                     @PathVariable UUID id) {
        return flagQueryService.detail(id, admin.businessId());
    }
}
