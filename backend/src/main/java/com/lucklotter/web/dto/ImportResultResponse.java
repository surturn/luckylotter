package com.lucklotter.web.dto;

import java.util.List;

/**
 * Outcome of a CSV import (FR-1, §12).
 *
 * <p>Reports per-row results rather than failing the whole file on the first
 * bad line: a POS export with one malformed row is normal, and rejecting the
 * other four thousand would make the importer useless in exactly the situation
 * it exists for.
 *
 * @param duplicates rows whose transaction ID was already ingested — not
 *                   errors, just replays (NFR-3)
 */
public record ImportResultResponse(
    int totalRows,
    int imported,
    int duplicates,
    int failed,
    List<RowError> errors
) {

    /**
     * @param line    1-based line number in the uploaded file, so the user can
     *                find the row in their spreadsheet
     * @param message what was wrong with it — never echoes the row's contents,
     *                which would put customer contact details in a response
     *                body and from there into a log (NFR-4)
     */
    public record RowError(int line, String message) {
    }
}
