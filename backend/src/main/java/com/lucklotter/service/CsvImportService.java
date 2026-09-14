package com.lucklotter.service;

import com.lucklotter.support.Redact;
import com.lucklotter.web.dto.ImportPreviewResponse;
import com.lucklotter.web.dto.ImportResultResponse;
import com.lucklotter.web.dto.ImportResultResponse.RowError;
import com.lucklotter.web.dto.TransactionIngestRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk ingestion from a POS transaction export (FR-1, §12).
 *
 * <p>Every row goes through {@link IngestionService}, so an imported
 * transaction is indistinguishable from one posted to the API: same customer
 * upsert, same cadence recomputation, same idempotency, same flag resolution.
 * A separate import path would be a second implementation of the rules that
 * matter most.
 *
 * <p>Column names come from the caller's mapping rather than a fixed header,
 * because no two till vendors agree on what to call a receipt number.
 */
@Service
public class CsvImportService {

    private static final Logger log = LoggerFactory.getLogger(CsvImportService.class);

    /** Bounded so a runaway upload can't be turned into an out-of-memory. */
    private static final int MAX_ROWS = 50_000;
    /** Enough to fix a broken export; past that the file itself is the problem. */
    private static final int MAX_REPORTED_ERRORS = 50;
    /** Enough for an admin to recognise a column by its contents. */
    private static final int PREVIEW_ROWS = 4;
    /**
     * Enough failures to see the pattern — one bad date format, one missing
     * column — without turning the mapping panel into a report. The complete
     * list belongs to the import result, which is where a fix gets checked.
     */
    private static final int PREVIEW_ERROR_SAMPLES = 10;

    private final IngestionService ingestion;

    public CsvImportService(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /**
     * Reads the header and a few rows so the admin can map columns, and — once
     * a mapping has been confirmed — what importing the file would do. Imports
     * nothing either way.
     *
     * <p>Without a mapping there is nothing honest to count: which column holds
     * the customer, the amount or the date is the very thing still being
     * decided, and counting from the suggestion would put confident figures
     * beside columns the admin may be about to change.
     */
    public ImportPreviewResponse preview(UUID businessId, MultipartFile file, ColumnMapping mapping) {
        try (BufferedReader reader = open(file)) {
            List<String> header = readHeader(reader);

            // Resolved before a single row is read, so a column that is not in
            // the file fails the preview outright instead of surfacing as every
            // row being unreadable.
            Map<String, Integer> columns = mapping != null && mapping.isComplete()
                    ? resolve(header, mapping)
                    : null;

            List<List<String>> samples = new ArrayList<>();
            DryRun dryRun = columns == null ? null : new DryRun(businessId, columns);

            // One pass for both: the samples and the counts are two views of the
            // same rows, and reading them separately would cost a second scan of
            // a file that can hold fifty thousand of them.
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                List<String> row = parseLine(line);
                if (samples.size() < PREVIEW_ROWS) {
                    samples.add(row);
                }
                if (dryRun != null && !dryRun.accept(lineNumber, row)) {
                    break;
                }
            }

            ColumnMapping suggestion = ColumnMapping.suggestFor(header);
            Map<String, String> suggested = new LinkedHashMap<>();
            suggestion.asMap().forEach((field, column) -> {
                if (column != null) {
                    suggested.put(field, column);
                }
            });

            // Row contents are the admin's own data and never leave this
            // response — the log records shape only (NFR-4).
            log.info("CSV preview: columns={} sampleRows={} autoMapped={} counted={}",
                    header.size(), samples.size(), suggested.size(), dryRun != null);
            return new ImportPreviewResponse(header, samples, suggested,
                    dryRun == null ? null : dryRun.toStats());
        } catch (IOException e) {
            throw new ValidationException("The file could not be read: " + e.getMessage());
        }
    }

    public ImportResultResponse importCsv(UUID businessId, MultipartFile file, ColumnMapping mapping) {
        if (!mapping.isComplete()) {
            throw new ValidationException(
                    "Map a column to each of customerRef, externalTxnId, amount and occurredAt before importing");
        }

        int total = 0;
        int imported = 0;
        int duplicates = 0;
        int failed = 0;
        List<RowError> errors = new ArrayList<>();

        try (BufferedReader reader = open(file)) {
            Map<String, Integer> columns = resolve(readHeader(reader), mapping);

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (total >= MAX_ROWS) {
                    throw new ValidationException(
                            "This file has more than " + MAX_ROWS + " rows — split it and import in parts");
                }
                total++;

                try {
                    TransactionIngestRequest request = toRequest(businessId, parseLine(line), columns);
                    if (ingestion.ingest(businessId, request).duplicate()) {
                        duplicates++;
                    } else {
                        imported++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    if (errors.size() < MAX_REPORTED_ERRORS) {
                        errors.add(new RowError(lineNumber, describe(e)));
                    }
                }
            }
        } catch (IOException e) {
            throw new ValidationException("The file could not be read: " + e.getMessage());
        }

        log.info("CSV import finished: businessId={} rows={} imported={} duplicates={} failed={}",
                businessId, total, imported, duplicates, failed);
        return new ImportResultResponse(total, imported, duplicates, failed, errors);
    }

    private static BufferedReader open(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ValidationException("The uploaded file is empty");
        }
        return new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
    }

    private static List<String> readHeader(BufferedReader reader) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) {
            throw new ValidationException("The uploaded file has no header row");
        }
        return parseLine(stripBom(headerLine));
    }

    /**
     * Turns the admin's chosen header names into column positions.
     *
     * <p>Matching is normalised, so a mapping survives the difference between
     * "Cust ID" as displayed and "cust_id" as written.
     */
    private static Map<String, Integer> resolve(List<String> header, ColumnMapping mapping) {
        Map<String, Integer> byNormalisedHeader = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            byNormalisedHeader.putIfAbsent(ColumnMapping.normalise(header.get(index)), index);
        }

        Map<String, Integer> resolved = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        mapping.asMap().forEach((field, column) -> {
            if (column == null) {
                return;
            }
            Integer index = byNormalisedHeader.get(ColumnMapping.normalise(column));
            if (index == null) {
                unknown.add(column);
            } else {
                resolved.put(field, index);
            }
        });

        if (!unknown.isEmpty()) {
            throw new ValidationException(
                    "These columns are not in the file: " + String.join(", ", unknown));
        }
        return resolved;
    }

    private static TransactionIngestRequest toRequest(UUID businessId, List<String> row,
                                                      Map<String, Integer> columns) {
        String customerRef = required(row, columns, ColumnMapping.CUSTOMER_REF, "customer reference");
        String externalTxnId = required(row, columns, ColumnMapping.EXTERNAL_TXN_ID, "transaction ID");
        String rawAmount = required(row, columns, ColumnMapping.AMOUNT, "amount");

        BigDecimal amount;
        try {
            // Thousands separators and a stray currency prefix are normal in
            // spreadsheet exports.
            amount = new BigDecimal(rawAmount.replaceAll("[,\\s]", "").replaceAll("^[^0-9.\\-]+", ""));
        } catch (NumberFormatException e) {
            throw new ValidationException("amount is not a number");
        }
        if (amount.signum() < 0) {
            throw new ValidationException("amount cannot be negative");
        }

        return new TransactionIngestRequest(
                businessId,
                customerRef,
                optional(row, columns, ColumnMapping.CUSTOMER_NAME),
                optional(row, columns, ColumnMapping.USUAL_ITEM),
                externalTxnId,
                amount,
                parseTimestamp(required(row, columns, ColumnMapping.OCCURRED_AT, "date")),
                optional(row, columns, ColumnMapping.CONTACT_EMAIL),
                optional(row, columns, ColumnMapping.CONTACT_PHONE));
    }

    /**
     * Accepts an ISO instant, a local date-time, or a plain date — the three
     * shapes POS exports actually produce. A bare date is read as midnight UTC.
     */
    private static Instant parseTimestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Fall through to the looser formats.
        }
        try {
            return LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Fall through.
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            throw new ValidationException(
                    "date is not recognisable - use 2026-07-28, 2026-07-28 09:30:00,"
                    + " or 2026-07-28T09:30:00Z");
        }
    }

    private static String required(List<String> row, Map<String, Integer> columns,
                                   String field, String label) {
        String value = optional(row, columns, field);
        if (value == null) {
            throw new ValidationException(label + " is missing");
        }
        return value;
    }

    private static String optional(List<String> row, Map<String, Integer> columns, String field) {
        Integer index = columns.get(field);
        if (index == null || index >= row.size()) {
            return null;
        }
        String value = row.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * One file counted against one mapping, writing nothing.
     *
     * <p>The rows go through the same conversion the import uses, which is what
     * makes the counts worth showing: "97% valid" describes this file against
     * this mapping rather than a second estimate that could disagree with what
     * the import then reports.
     */
    private static final class DryRun {

        private final UUID businessId;
        private final Map<String, Integer> columns;

        private int rowCount;
        private int valid;
        private int invalid;
        private boolean truncated;
        private final List<RowError> failures = new ArrayList<>();
        private final Set<String> customers = new HashSet<>();
        private Instant earliest;
        private Instant latest;

        private DryRun(UUID businessId, Map<String, Integer> columns) {
            this.businessId = businessId;
            this.columns = columns;
        }

        /**
         * Counts one row.
         *
         * @return false when the file holds more rows than can be imported, so
         *         the caller stops reading. A preview that exists to warn about
         *         the size of a file should say so rather than fail the request
         */
        private boolean accept(int lineNumber, List<String> row) {
            if (rowCount >= MAX_ROWS) {
                truncated = true;
                return false;
            }
            rowCount++;

            try {
                TransactionIngestRequest request = toRequest(businessId, row, columns);
                valid++;
                customers.add(request.customerRef());
                Instant occurredAt = request.occurredAt();
                if (earliest == null || occurredAt.isBefore(earliest)) {
                    earliest = occurredAt;
                }
                if (latest == null || occurredAt.isAfter(latest)) {
                    latest = occurredAt;
                }
            } catch (RuntimeException e) {
                invalid++;
                if (failures.size() < PREVIEW_ERROR_SAMPLES) {
                    failures.add(new RowError(lineNumber, describe(e)));
                }
            }
            return true;
        }

        private ImportPreviewResponse.PreviewStats toStats() {
            return new ImportPreviewResponse.PreviewStats(
                    rowCount, valid, invalid, failures, customers.size(), earliest, latest, truncated);
        }
    }

    /** Messages describe the problem, never the row — see {@link RowError}. */
    private static String describe(RuntimeException e) {
        if (e instanceof ValidationException || e instanceof NotFoundException) {
            return e.getMessage();
        }
        // Scrubbed: a parse or mapping failure quotes the row it choked on, and
        // a POS row is the richest contact data in the system (NFR-4).
        log.warn("Unexpected error importing a CSV row: cause={}", Redact.scrubStackTrace(e));
        return "Could not be imported";
    }

    /**
     * Minimal RFC 4180 parsing: quoted fields, embedded commas, and doubled
     * quotes. A plain {@code split(",")} corrupts any export with a comma in a
     * customer name, which is most of them.
     */
    private static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quoted) {
                if (character == '"') {
                    boolean escapedQuote = index + 1 < line.length() && line.charAt(index + 1) == '"';
                    if (escapedQuote) {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(character);
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    /** Excel writes a UTF-8 BOM, which would otherwise corrupt the first header. */
    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }
}
