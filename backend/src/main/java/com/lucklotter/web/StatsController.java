package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.OverviewStatsService;
import com.lucklotter.service.ReportingWindow;
import com.lucklotter.service.RetentionDiagnosticService;
import com.lucklotter.service.RetentionImpactService;
import com.lucklotter.web.dto.OverviewStatsResponse;
import com.lucklotter.web.dto.RetentionDiagnosticResponse;
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
    private final RetentionDiagnosticService diagnostics;

    public StatsController(OverviewStatsService overviewStats,
                           RetentionImpactService retentionImpact,
                           RetentionDiagnosticService diagnostics) {
        this.overviewStats = overviewStats;
        this.retentionImpact = retentionImpact;
        this.diagnostics = diagnostics;
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

    /**
     * The retention diagnostic (§7), whole, as one document.
     *
     * <p>No reporting window: this report is about the state of the customer
     * base, and its two halves are deliberately on different clocks already —
     * the behaviour section describes everyone's whole history, and the money
     * section totals the whole programme. A window parameter here would be a
     * third clock, and the reader would have no way to tell which figures it
     * moved. The dashboard's selector is the place to narrow a period.
     */
    @GetMapping("/diagnostic")
    public RetentionDiagnosticResponse diagnostic(@AuthenticationPrincipal AdminPrincipal admin) {
        return diagnostics.forBusiness(admin.businessId());
    }
}
