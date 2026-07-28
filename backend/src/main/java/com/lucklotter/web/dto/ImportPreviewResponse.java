package com.lucklotter.web.dto;

import java.util.List;
import java.util.Map;

/**
 * What a CSV looks like before anything is imported (FR-1, §12).
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
 */
public record ImportPreviewResponse(
    List<String> columns,
    List<List<String>> sampleRows,
    Map<String, String> suggested
) {
}
