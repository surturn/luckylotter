package com.lucklotter.web.dto;

import com.lucklotter.web.dto.ImportResultResponse.RowError;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What a CSV looks like before anything is imported (FR-1, §8).
 *
 * <p>Exists so the admin can map their POS's column names onto ours. Every till
 * vendor names these differently — "Cust ID", "Receipt No", "Total" — and
 * requiring an exact header would mean asking a café owner to edit a
 * spreadsheet before they can use the product.
 *
 * @param columns     header names exactly as they appear in the file
 * @param sampleRows  first few rows, so the admin can see which column holds
 *                    what rather than guessing from the name
 * @param suggested   our best guess, keyed by our field name — a starting
 *                    point the admin can override, never applied silently
 * @param stats       a dry run of the whole file through a confirmed mapping,
 *                    or null when no mapping has been supplied yet
 */
public record ImportPreviewResponse(
    List<String> columns,
    List<List<String>> sampleRows,
    Map<String, String> suggested,
    PreviewStats stats
) {

    /**
     * What the file would do if it were imported now, with nothing imported.
     *
     * <p>The counts come from the same row conversion the import runs, so
     * "97% valid" is a statement about this file against this mapping rather
     * than a separate estimate that could disagree with the import result.
     *
     * @param rowCount         data rows in the file, header excluded
     * @param validRows        rows that converted to a transaction
     * @param invalidRowCount  rows that did not, including any past the sample
     * @param invalidSamples   the first few failures, by line number — the same
     *                         shape the import result reports them in, so a file
     *                         can be fixed before it is imported rather than after
     * @param uniqueCustomers  distinct customer references among the valid rows
     * @param earliest         first sale date among the valid rows, null if none
     * @param latest           last sale date among the valid rows, null if none
     * @param truncated        true when the file has more rows than can be
     *                         imported, so the counts describe only what was read
     */
    public record PreviewStats(
        int rowCount,
        int validRows,
        int invalidRowCount,
        List<RowError> invalidSamples,
        int uniqueCustomers,
        Instant earliest,
        Instant latest,
        boolean truncated
    ) {
    }
}
