package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.OverviewStatsService;
import com.lucklotter.service.RetentionImpactService;
import com.lucklotter.web.dto.OverviewStatsResponse;
import com.lucklotter.web.dto.RetentionImpactResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard aggregates (FR-7). Read-only, and scoped to the caller (NFR-1). */
@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    private final OverviewStatsService overviewStats;
    private final RetentionImpactService retentionImpact;

    public StatsController(OverviewStatsService overviewStats,
                           RetentionImpactService retentionImpact) {
        this.overviewStats = overviewStats;
        this.retentionImpact = retentionImpact;
    }

    @GetMapping("/overview")
    public OverviewStatsResponse overview(@AuthenticationPrincipal AdminPrincipal admin) {
        return overviewStats.forBusiness(admin.businessId());
    }

    /**
     * The economic section, separate from the counts on purpose: it is the one
     * part of the dashboard whose every figure depends on a stated definition
     * and a measurement window, and bundling it into the overview response would
     * invite a caller to render a revenue number without the definition that
     * makes it mean something.
     */
    @GetMapping("/retention-impact")
    public RetentionImpactResponse retentionImpact(@AuthenticationPrincipal AdminPrincipal admin) {
        return retentionImpact.forBusiness(admin.businessId());
    }
}
