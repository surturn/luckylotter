package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.CadenceRebuildService;
import com.lucklotter.service.RetentionScanService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the cadence-break scan on demand, scoped to the caller's own business.
 *
 * <p>Exists so a demo doesn't have to wait for the nightly schedule. The scan
 * is idempotent, so triggering it repeatedly is harmless — a customer already
 * flagged stays at one flag (FR-8).
 */
@RestController
@RequestMapping("/v1/admin/retention")
public class RetentionJobController {

    private final RetentionScanService retentionScanService;
    private final CadenceRebuildService cadenceRebuildService;

    public RetentionJobController(RetentionScanService retentionScanService,
                                  CadenceRebuildService cadenceRebuildService) {
        this.retentionScanService = retentionScanService;
        this.cadenceRebuildService = cadenceRebuildService;
    }

    @PostMapping("/run")
    public RetentionScanService.ScanSummary run(@AuthenticationPrincipal AdminPrincipal admin) {
        return retentionScanService.scan(admin.businessId());
    }

    /**
     * Rebuilds every customer's cadence from their transaction history (FR-2).
     *
     * <p>Needed after a bulk import, and once after the visit-collapse change:
     * ingestion only recomputes a customer's cadence when they next transact, so
     * until this runs, imported history keeps averages derived the old way — and
     * those averages set the flag threshold.
     *
     * <p>Scoped to the caller's own business, and safe to repeat: it derives
     * everything from transactions rather than adjusting what is already stored.
     * It deliberately leaves flags alone, so run it before the next scan rather
     * than expecting it to correct flags already raised.
     */
    @PostMapping("/rebuild-cadence")
    public CadenceRebuildService.RebuildSummary rebuildCadence(
            @AuthenticationPrincipal AdminPrincipal admin) {
        return cadenceRebuildService.rebuild(admin.businessId());
    }
}
