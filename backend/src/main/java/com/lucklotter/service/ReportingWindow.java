package com.lucklotter.service;

import java.time.temporal.ChronoUnit;

/**
 * The period the dashboard reports over.
 *
 * <p>A reporting choice and nothing more. It bounds which events are counted on
 * the way out; it does not bound what the cadence model reads on the way in.
 * Every customer's rhythm, threshold and drift status is derived from their own
 * complete visit history, so moving this selector changes what the dashboard
 * shows and never who gets flagged.
 *
 * <p>That separation is the whole reason this type exists. A reporting window
 * wired into the model would mean two operators looking at the same business,
 * on different settings, disagreeing about who is drifting — and the customer
 * who vanishes from the flag list is the one nobody calls.
 */
public enum ReportingWindow {

    LAST_7_DAYS(7, "Last 7 days", ChronoUnit.DAYS),
    LAST_30_DAYS(30, "Last 30 days", ChronoUnit.DAYS),
    LAST_8_WEEKS(56, "Last 8 weeks", ChronoUnit.WEEKS);

    /** The default: the window the dashboard has always reported over. */
    public static final ReportingWindow DEFAULT = LAST_8_WEEKS;

    private final int days;
    private final String label;
    private final ChronoUnit bucket;

    ReportingWindow(int days, String label, ChronoUnit bucket) {
        this.days = days;
        this.label = label;
        this.bucket = bucket;
    }

    public int days() {
        return days;
    }

    public String label() {
        return label;
    }

    /**
     * How the activity series is cut up. Follows the window rather than being
     * fixed: a week is a useful division of eight weeks and a useless one of
     * seven days, where it would collapse the chart to a single bar.
     */
    public ChronoUnit bucket() {
        return bucket;
    }
}
