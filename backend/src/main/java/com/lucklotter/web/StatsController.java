package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.OverviewStatsService;
import com.lucklotter.web.dto.OverviewStatsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard aggregates (FR-7). Read-only, and scoped to the caller (NFR-1). */
@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    private final OverviewStatsService overviewStats;

    public StatsController(OverviewStatsService overviewStats) {
        this.overviewStats = overviewStats;
    }

    @GetMapping("/overview")
    public OverviewStatsResponse overview(@AuthenticationPrincipal AdminPrincipal admin) {
        return overviewStats.forBusiness(admin.businessId());
    }
}
