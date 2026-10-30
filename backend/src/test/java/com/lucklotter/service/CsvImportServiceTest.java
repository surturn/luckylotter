package com.lucklotter.service;

import com.lucklotter.domain.Business;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.web.dto.ImportPreviewResponse;
import com.lucklotter.web.dto.ImportResultResponse.RowError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    private final CsvImportService csv = serviceIn("UTC");

    private static final UUID BUSINESS = UUID.randomUUID();

    /**
     * Fixed, so the rows dated in July 2026 stay in the past however long this
     * suite lives, and "in the future" is a fixed line the tests can cross.
     */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC);

    /** A service whose business keeps the given timezone. */
    private static CsvImportService serviceIn(String timezone) {
        Business business = new Business();
        business.setTimezone(timezone);
        BusinessRepository repository = mock(BusinessRepository.class);
        when(repository.findById(any())).thenReturn(Optional.of(business));
        return new CsvImportService(null, repository, CLOCK);
    }

    private static final String HEADER = "Cust ID,Receipt No,Total,Date";

    /** Three good rows and two that cannot be read. */
    private static final String EXPORT = HEADER + "\n"
            + "C1,R1,450.00,2026-07-01\n"
            + "C2,R2,300.00,2026-07-02\n"
            + ",R3,200.00,2026-07-03\n"
            + "C1,R4,100.00,not-a-date\n"
            + "C1,R5,50.00,2026-07-05\n";

    private static final ColumnMapping MAPPING = new ColumnMapping(
            "Cust ID", null, null, "Receipt No", "Total", "Date", null, null, null);

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

    // --- booking exports: cancellations, bookings ahead, local time --------------

    private static final String BOOKING_HEADER = "Client ID,Appointment ID,Price,Appointment Date,Status";

    private static final ColumnMapping BOOKING_MAPPING = new ColumnMapping(
            "Client ID", null, null, "Appointment ID", "Price", "Appointment Date", null, null, "Status");

    @Test
    @DisplayName("cancelled and no-show appointments are left out and counted, not treated as visits or errors")
    void cancelledRowsAreSkipped() {
        // A no-show is exactly the signal the product exists to notice, so
        // counting it as a visit would hide the lapse. The cancelled row here
        // has no price at all, which would be an error if it were read.
        String export = BOOKING_HEADER + "\n"
                + "C1,B1,2500,2026-07-01,Completed\n"
                + "C1,B2,,2026-07-02,Cancelled\n"
                + "C2,B3,1800,2026-07-03,No-show\n"
                + "C2,B4,1800,2026-07-04,NO SHOW\n"
                + "C2,B5,1800,2026-07-05,completed\n";

        ImportPreviewResponse.PreviewStats stats = preview(export, BOOKING_MAPPING).stats();

        assertThat(stats.rowCount()).isEqualTo(5);
        assertThat(stats.validRows()).isEqualTo(2);
        assertThat(stats.skippedCancelled()).isEqualTo(3);
        assertThat(stats.invalidRowCount()).as("a skipped row is not a failed one").isZero();
    }

    @Test
    @DisplayName("appointments dated in the future are left out and counted")
    void futureRowsAreSkipped() {
        String export = BOOKING_HEADER + "\n"
                + "C1,B1,2500,2026-07-01,Completed\n"
                + "C1,B2,2500,2026-07-20,Booked\n"
                + "C1,B3,2500,2026-08-15,Booked\n";

        ImportPreviewResponse.PreviewStats stats = preview(export, BOOKING_MAPPING).stats();

        assertThat(stats.validRows()).isEqualTo(1);
        assertThat(stats.skippedFuture()).isEqualTo(2);
        // The range describes what would import, so a booking next month does
        // not stretch it.
        assertThat(stats.latest()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
    }

    @Test
    @DisplayName("without a status column mapped nothing is skipped as cancelled")
    void statusColumnIsOptional() {
        ColumnMapping withoutStatus = new ColumnMapping(
                "Client ID", null, null, "Appointment ID", "Price", "Appointment Date", null, null, null);
        String export = BOOKING_HEADER + "\nC1,B1,2500,2026-07-01,Cancelled\n";

        ImportPreviewResponse.PreviewStats stats = preview(export, withoutStatus).stats();

        assertThat(stats.skippedCancelled()).isZero();
        assertThat(stats.validRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("a date with no zone is read in the business timezone, not UTC")
    void localTimesAreReadInTheBusinessZone() {
        // Los Angeles is UTC-7 in July: midnight there is 07:00 UTC, and a
        // 6:30pm appointment is already tomorrow in UTC. Read as UTC, the
        // evening booking would be filed on the wrong day.
        CsvImportService losAngeles = serviceIn("America/Los_Angeles");
        String export = "Client ID,Appointment ID,Price,Appointment Date\n"
                + "C1,B1,100,2026-07-01\n"
                + "C1,B2,100,2026-07-02 18:30:00\n";
        ColumnMapping mapping = new ColumnMapping(
                "Client ID", null, null, "Appointment ID", "Price", "Appointment Date", null, null, null);

        ImportPreviewResponse.PreviewStats stats = losAngeles.preview(BUSINESS,
                new MockMultipartFile("file", "x.csv", "text/csv",
                        export.getBytes(StandardCharsets.UTF_8)), mapping).stats();

        assertThat(stats.earliest()).isEqualTo(Instant.parse("2026-07-01T07:00:00Z"));
        assertThat(stats.latest()).isEqualTo(Instant.parse("2026-07-03T01:30:00Z"));
    }

    @Test
    @DisplayName("a booking system's usual headers are recognised without hand-mapping")
    void bookingHeadersAreSuggested() {
        ColumnMapping suggested = ColumnMapping.suggestFor(java.util.List.of(
                "Client ID", "Client Name", "Appointment ID", "Appointment Date", "Service",
                "Price", "Status", "Cell", "Email"));

        assertThat(suggested.customerRef()).isEqualTo("Client ID");
        assertThat(suggested.customerName()).isEqualTo("Client Name");
        assertThat(suggested.externalTxnId()).isEqualTo("Appointment ID");
        assertThat(suggested.occurredAt()).isEqualTo("Appointment Date");
        assertThat(suggested.usualItem()).isEqualTo("Service");
        assertThat(suggested.amount()).isEqualTo("Price");
        assertThat(suggested.status()).isEqualTo("Status");
        assertThat(suggested.contactPhone()).isEqualTo("Cell");
        assertThat(suggested.isComplete()).isTrue();
    }

    @Test
    @DisplayName("status values match however they are spelled")
    void statusMatchingIgnoresPunctuationAndCase() {
        assertThat(ColumnMapping.isSkippedStatus("No-Show")).isTrue();
        assertThat(ColumnMapping.isSkippedStatus("no_show")).isTrue();
        assertThat(ColumnMapping.isSkippedStatus("CANCELLED")).isTrue();
        assertThat(ColumnMapping.isSkippedStatus("Canceled")).isTrue();
        assertThat(ColumnMapping.isSkippedStatus("Completed")).isFalse();
        assertThat(ColumnMapping.isSkippedStatus("Checked in")).isFalse();
        assertThat(ColumnMapping.isSkippedStatus(null)).isFalse();
    }

    private ImportPreviewResponse preview(String content, ColumnMapping mapping) {
        MockMultipartFile file = new MockMultipartFile(
                "file", "export.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
        return csv.preview(BUSINESS, file, mapping);
    }
}
