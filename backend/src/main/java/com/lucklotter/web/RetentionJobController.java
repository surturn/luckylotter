package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
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

    public RetentionJobController(RetentionScanService retentionScanService) {
        this.retentionScanService = retentionScanService;
    }

    @PostMapping("/run")
    public RetentionScanService.ScanSummary run(@AuthenticationPrincipal AdminPrincipal admin) {
        return retentionScanService.scan(admin.businessId());
    }
}
