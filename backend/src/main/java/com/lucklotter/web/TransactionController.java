package com.lucklotter.web;

import com.lucklotter.security.AdminPrincipal;
import com.lucklotter.service.ColumnMapping;
import com.lucklotter.service.CsvImportService;
import com.lucklotter.service.IngestionService;
import com.lucklotter.web.dto.ImportPreviewResponse;
import com.lucklotter.web.dto.ImportResultResponse;
import com.lucklotter.web.dto.TransactionIngestRequest;
import com.lucklotter.web.dto.TransactionIngestResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** POS transaction ingestion (FR-1, NFR-3, §10). */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    private final IngestionService ingestionService;
    private final CsvImportService csvImportService;

    public TransactionController(IngestionService ingestionService,
                                 CsvImportService csvImportService) {
        this.ingestionService = ingestionService;
        this.csvImportService = csvImportService;
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

    /**
     * Reads a CSV's header and first rows so the admin can map their columns
     * onto ours (FR-1, §12). Imports nothing.
     */
    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreviewResponse previewCsv(@AuthenticationPrincipal AdminPrincipal admin,
                                            @RequestPart("file") MultipartFile file) {
        return csvImportService.preview(file);
    }

    /**
     * Bulk import of a POS export (FR-1, §12).
     *
     * <p>Scoped to the caller's business from the token, so a CSV cannot carry
     * rows into another tenant no matter what it contains (NFR-1).
     *
     * <p>Column names are supplied by the caller rather than assumed: the
     * mapping is confirmed by a human in the preview step, because guessing
     * which column is the transaction ID would silently break idempotency.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultResponse importCsv(@AuthenticationPrincipal AdminPrincipal admin,
                                          @RequestPart("file") MultipartFile file,
                                          @RequestParam("customerRef") String customerRef,
                                          @RequestParam(value = "customerName", required = false) String customerName,
                                          @RequestParam(value = "usualItem", required = false) String usualItem,
                                          @RequestParam("externalTxnId") String externalTxnId,
                                          @RequestParam("amount") String amount,
                                          @RequestParam("occurredAt") String occurredAt,
                                          @RequestParam(value = "contactEmail", required = false) String contactEmail,
                                          @RequestParam(value = "contactPhone", required = false) String contactPhone) {
        ColumnMapping mapping = new ColumnMapping(customerRef, customerName, usualItem,
                externalTxnId, amount, occurredAt, contactEmail, contactPhone);
        return csvImportService.importCsv(admin.businessId(), file, mapping);
    }
}
