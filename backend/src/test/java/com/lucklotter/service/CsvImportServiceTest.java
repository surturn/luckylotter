package com.lucklotter.service;

import com.lucklotter.web.dto.ImportPreviewResponse;
import com.lucklotter.web.dto.ImportResultResponse.RowError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import preview's dry run (FR-1, §8).
 *
 * <p>These are the numbers an admin is asked to trust before committing a file,
 * so they are asserted against a file whose every row is known: the counts, the
 * date range, and the line numbers of the rows that would fail.
 */
class CsvImportServiceTest {

    /**
     * No ingestion service: these tests only reach {@code preview}, which parses
     * and counts but writes nothing — that is the property under test, and the
     * preview has no other collaborator to offer.
     */
    private final CsvImportService csv = new CsvImportService(null);

    private static final UUID BUSINESS = UUID.randomUUID();

    private static final String HEADER = "Cust ID,Receipt No,Total,Date";

    /** Three good rows and two that cannot be read. */
    private static final String EXPORT = HEADER + "\n"
            + "C1,R1,450.00,2026-07-01\n"
            + "C2,R2,300.00,2026-07-02\n"
            + ",R3,200.00,2026-07-03\n"
            + "C1,R4,100.00,not-a-date\n"
            + "C1,R5,50.00,2026-07-05\n";

    private static final ColumnMapping MAPPING = new ColumnMapping(
            "Cust ID", null, null, "Receipt No", "Total", "Date", null, null);

    @Test
    @DisplayName("the preview counts what importing the file would do")
    void previewCountsWhatImportingWouldDo() {
        ImportPreviewResponse.PreviewStats stats = preview(EXPORT, MAPPING).stats();

        assertThat(stats).isNotNull();
        assertThat(stats.rowCount()).isEqualTo(5);
        assertThat(stats.validRows()).isEqualTo(3);
        assertThat(stats.invalidRowCount()).isEqualTo(2);
        assertThat(stats.uniqueCustomers())
                .as("two refs among the readable rows; the rows that failed carry none")
                .isEqualTo(2);
        assertThat(stats.truncated()).isFalse();
    }

    @Test
    @DisplayName("the date range comes from the rows that would actually import")
    void dateRangeIgnoresUnreadableRows() {
        ImportPreviewResponse.PreviewStats stats = preview(EXPORT, MAPPING).stats();

        assertThat(stats.earliest()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(stats.latest()).isEqualTo(Instant.parse("2026-07-05T00:00:00Z"));
    }

    @Test
    @DisplayName("the failures are named by line number, so they can be found in the file")
    void failuresAreNamedByLineNumber() {
        ImportPreviewResponse.PreviewStats stats = preview(EXPORT, MAPPING).stats();

        // The header is line 1, so the third and fourth data rows are lines 4
        // and 5 of the file — the number a spreadsheet shows.
        assertThat(stats.invalidSamples()).extracting(RowError::line).containsExactly(4, 5);
        assertThat(stats.invalidSamples()).extracting(RowError::message)
                .containsExactly("customer reference is missing", "date is not recognisable - use "
                        + "2026-07-28, 2026-07-28 09:30:00, or 2026-07-28T09:30:00Z");
    }

    @Test
    @DisplayName("a preview with no confirmed mapping counts nothing rather than guessing")
    void noMappingMeansNoCounts() {
        // The mapping is what is still being decided at this point, so any
        // figure here would be computed from columns the admin may be about to
        // change — a confident number that is quietly wrong.
        ImportPreviewResponse unconfirmed = preview(EXPORT, ColumnMapping.suggestFor(java.util.List.of()));

        assertThat(unconfirmed.stats()).isNull();
        assertThat(unconfirmed.columns()).containsExactly("Cust ID", "Receipt No", "Total", "Date");
        assertThat(unconfirmed.sampleRows()).hasSize(4);
    }

    @Test
    @DisplayName("an empty file beyond the header is not an error")
    void headerOnlyFileCountsZeroRows() {
        ImportPreviewResponse.PreviewStats stats = preview(HEADER + "\n", MAPPING).stats();

        assertThat(stats.rowCount()).isZero();
        assertThat(stats.validRows()).isZero();
        assertThat(stats.earliest()).isNull();
        assertThat(stats.latest()).isNull();
    }

    private ImportPreviewResponse preview(String content, ColumnMapping mapping) {
        MockMultipartFile file = new MockMultipartFile(
                "file", "export.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        return csv.preview(BUSINESS, file, mapping);
    }
}
