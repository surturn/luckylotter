package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.IngestionService;
import com.lucklotter.web.dto.TransactionIngestRequest;
import com.lucklotter.web.dto.TransactionIngestResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POS transaction ingestion (FR-1, NFR-3, §10). */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    private final IngestionService ingestionService;

    public TransactionController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * The request carries a {@code businessId} because the PRD's ingestion
     * payload defines one (FR-1), but it is treated as an assertion to check,
     * never as the tenant scope: the scope comes from the token, and a mismatch
     * is refused rather than silently honouring one or the other (NFR-1).
     */
    @PostMapping
    public TransactionIngestResponse ingest(@AuthenticationPrincipal AdminPrincipal admin,
                                            @Valid @RequestBody TransactionIngestRequest request) {
        if (!admin.businessId().equals(request.businessId())) {
            throw new AccessDeniedException("businessId does not match the authenticated business");
        }
        return ingestionService.ingest(admin.businessId(), request);
    }
}
