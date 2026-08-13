package com.lucklotter.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the log scrubber (NFR-4). Plain unit tests — no database, since
 * this is string handling on the way to an appender.
 *
 * <p>The cases are drawn from the text that actually reaches these call sites:
 * SMTP rejections, CSV parse errors and Postgres constraint messages.
 */
class RedactTest {

    @Test
    @DisplayName("an SMTP rejection keeps its diagnosis and loses the recipient")
    void smtpRejection() {
        String scrubbed = Redact.scrub("550 5.1.1 <regular@example.com> user unknown");

        assertThat(scrubbed).doesNotContain("regular@example.com");
        // The half that makes the failure actionable has to survive, or the
        // scrubbing has just traded a breach for an undiagnosable bug.
        assertThat(scrubbed).contains("550 5.1.1").contains("user unknown");
    }

    @Test
    @DisplayName("a status code is not mistaken for a phone number")
    void statusCodesSurvive() {
        // Regression: counting characters rather than digits, "550 5.1.1" is a
        // long enough run of digits and separators to look like a number, and
        // masking it removes the whole diagnosis. Six digits, not seven.
        assertThat(Redact.scrub("550 5.1.1 mailbox unavailable")).contains("550 5.1.1");
        assertThat(Redact.scrub("421 4.7.0 try again later")).contains("421 4.7.0");
    }

    @Test
    @DisplayName("phone numbers are masked in the shapes people write them")
    void phoneNumbers() {
        assertThat(Redact.scrub("failed for +254 712 345 678")).doesNotContain("712");
        assertThat(Redact.scrub("failed for (020) 7946-0958")).doesNotContain("7946");
        assertThat(Redact.scrub("failed for 07123456789")).doesNotContain("07123456789");
    }

    @Test
    @DisplayName("identifiers survive, or the logs stop being correlatable")
    void identifiersAreNotMasked() {
        // The whole logging strategy is "IDs and codes only", so a scrubber that
        // ate UUIDs would remove the one thing every log line depends on.
        String id = UUID.randomUUID().toString();
        assertThat(Redact.scrub("offerId=" + id)).contains(id);
        assertThat(Redact.scrub("code=SENDER_TIMEOUT")).contains("SENDER_TIMEOUT");
    }

    @Test
    @DisplayName("a CSV row loses its contact columns")
    void csvRow() {
        String scrubbed = Redact.scrub(
                "malformed row: [tx-9931, Ada Lovelace, ada@example.com, +254712345678, 12.50]");

        assertThat(scrubbed).doesNotContain("ada@example.com").doesNotContain("254712345678");
        assertThat(scrubbed).contains("malformed row").contains("tx-9931");
    }

    @Test
    @DisplayName("a stack trace keeps its frames and loses the address in its message")
    void stackTraceIsScrubbedButUsable() {
        Exception error = new IllegalStateException("no mailbox for someone@example.com");

        String scrubbed = Redact.scrubStackTrace(error);

        assertThat(scrubbed).doesNotContain("someone@example.com");
        assertThat(scrubbed)
                .contains("IllegalStateException")
                .contains("stackTraceIsScrubbedButUsable");
    }

    @Test
    @DisplayName("null and empty text are left alone")
    void nullSafety() {
        // These call sites run inside catch blocks, where a scrubber that threw
        // would replace a logged failure with an unlogged one.
        assertThat(Redact.scrub(null)).isNull();
        assertThat(Redact.scrub("")).isEmpty();
        assertThat(Redact.scrubStackTrace(null)).isEmpty();
    }
}
