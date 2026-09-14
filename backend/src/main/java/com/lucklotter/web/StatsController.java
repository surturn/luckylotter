package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.OverviewStatsService;
import com.lucklotter.service.ReportingWindow;
import com.lucklotter.service.RetentionImpactService;
import com.lucklotter.web.dto.OverviewStatsResponse;
import com.lucklotter.web.dto.RetentionImpactResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * The dashboard counters, over a reporting period the caller chooses.
     *
     * <p>The period reaches nothing but this read. It selects which events are
     * counted, never which customers are modelled — the cadence, the thresholds
     * and the flag list are the same on every setting. See
     * {@link com.lucklotter.service.ReportingWindow}.
     *
     * <p>Defaults rather than requiring the parameter, so the screen keeps
     * working if a caller doesn't know about windows yet.
     */
    @GetMapping("/overview")
    public OverviewStatsResponse overview(
            @AuthenticationPrincipal AdminPrincipal admin,
            @RequestParam(defaultValue = "LAST_8_WEEKS") ReportingWindow window) {
        return overviewStats.forBusiness(admin.businessId(), window);
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
