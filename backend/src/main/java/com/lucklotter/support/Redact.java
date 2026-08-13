package com.lucklotter.support;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.regex.Pattern;

/**
 * Strips contact details out of text the application did not write, before it
 * reaches a log (NFR-4).
 *
 * <p>Every log statement this project writes itself carries IDs and codes only.
 * The exposure is text that arrives from somewhere else: an SMTP rejection
 * quoting the recipient ({@code 550 5.1.1 <someone@example.com> user unknown}),
 * a CSV parse error quoting the row it choked on, a Jackson error quoting the
 * request payload, a Postgres constraint violation quoting the offending key.
 * All of those are genuinely useful for diagnosis and all of them can carry a
 * customer's email or phone number.
 *
 * <p>This is the same reasoning that keeps {@code offers.failure_code} a bounded
 * code set rather than a provider string, applied to the log stream instead of a
 * column.
 *
 * <p><strong>Biased toward over-redaction.</strong> The patterns will sometimes
 * mask something harmless — a date, an order reference that looks like a phone
 * number — and that is the right way to be wrong here: a redacted date costs a
 * little diagnostic detail, a leaked phone number is a breach. It is a strong
 * reduction, not a guarantee: no regex recognises every shape an identifier can
 * take, so this does not make it safe to log arbitrary third-party text on
 * purpose.
 */
public final class Redact {

    private static final String EMAIL_PLACEHOLDER = "<email redacted>";
    private static final String PHONE_PLACEHOLDER = "<phone redacted>";

    private static final Pattern EMAIL = Pattern.compile(
            "[\\w.!#$%&'*+/=?^`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");

    /**
     * A run of at least seven <em>digits</em>, allowing the separators people
     * write phone numbers with.
     *
     * <p>The leading lookahead counting digits is what keeps this off an SMTP
     * status code: {@code 550 5.1.1} is nine characters of digits and
     * separators but only six digits, and masking it would throw away the most
     * useful part of a delivery failure. Counting characters instead of digits
     * ate it, which is how this rule was found.
     *
     * <p>The surrounding guards keep it off identifiers that merely contain
     * digits: a UUID segment is preceded by {@code -} or a word character, so it
     * never starts a match, and a hex run breaks the digit count.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w-])\\+?(?=(?:[\\s().-]*\\d){7})\\d[\\d\\s().-]{5,}\\d(?![\\w-])");

    private Redact() {
    }

    /** @return {@code text} with any email address or phone number masked. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String scrubbed = EMAIL.matcher(text).replaceAll(EMAIL_PLACEHOLDER);
        return PHONE.matcher(scrubbed).replaceAll(PHONE_PLACEHOLDER);
    }

    /**
     * The exception's stack trace, rendered and scrubbed.
     *
     * <p>Passing the throwable to the logger directly would print its message
     * verbatim above the frames, which is exactly the text that needs masking.
     * Rendering it here keeps the frames — the part that makes an unmapped
     * failure diagnosable — without letting the message through unread.
     */
    public static String scrubStackTrace(Throwable error) {
        if (error == null) {
            return "";
        }
        StringWriter rendered = new StringWriter();
        error.printStackTrace(new PrintWriter(rendered));
        return scrub(rendered.toString());
    }
}
