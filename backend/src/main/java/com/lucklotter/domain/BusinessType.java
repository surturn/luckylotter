package com.lucklotter.domain;

import java.util.Locale;

/**
 * The kind of business, which decides two things only: the starting trigger
 * settings and the words used in front of the customer.
 *
 * <p>It never changes how the engine behaves. A customer is flagged when they go
 * quiet relative to their own rhythm, whatever the business sells; what differs
 * between a cafe and a spa is how long a rhythm is. A weekly cafe regular and a
 * client who sees their stylist every seven weeks need different ceilings on the
 * flag threshold, a different cooldown and a different window in which a return
 * still counts as the offer's doing, and the presets below set those. A preset
 * is a starting point: every value it seeds is editable afterwards.
 *
 * <p>The numbers assume a typical customer of that kind, which is stated beside
 * each one so a reader can disagree with it. They are deliberately round.
 */
public enum BusinessType {

    CAFE_RESTAURANT("Cafe or restaurant",
            new Preset(7, 3, 60, 30, 14, DealType.PERCENT_OFF),
            new Vocabulary("customer", "customers", "visit", "Usual order", "free item",
                    "%s, your %s is waiting at %s",
                    "We've still got it on the menu.",
                    "Here's a little something for when you're next passing:",
                    "Quote this code on your next visit")),

    /** Walk-in or booked, roughly every four weeks. */
    BARBERSHOP("Barbershop",
            new Preset(28, 7, 120, 45, 21, DealType.PERCENT_OFF),
            new Vocabulary("client", "clients", "visit", "Usual service", "free add-on",
                    "%s, time for your %s at %s?",
                    "Your chair is waiting.",
                    "Here's a little something for your next cut:",
                    "Mention this code when you book or walk in")),

    /** Colour, cut and finish, roughly every seven weeks. */
    HAIR_SALON("Hair salon",
            new Preset(49, 14, 180, 60, 28, DealType.PERCENT_OFF),
            new Vocabulary("client", "clients", "appointment", "Usual service", "free add-on",
                    "%s, time for your %s at %s?",
                    "We'd love to have you back in the chair.",
                    "Here's a little something for your next booking:",
                    "Mention this code when you book")),

    /** Treatments, roughly every nine weeks. The slowest rhythm of the four. */
    SPA_WELLNESS("Spa or wellness",
            new Preset(63, 21, 240, 90, 35, DealType.PERCENT_OFF),
            new Vocabulary("client", "clients", "appointment", "Usual treatment", "free add-on",
                    "%s, time for your %s at %s?",
                    "We'd love to welcome you back.",
                    "Here's a little something for your next treatment:",
                    "Mention this code when you book")),

    /** Neutral wording, and settings in the middle of the range. */
    OTHER("Other",
            new Preset(30, 7, 120, 45, 21, DealType.PERCENT_OFF),
            new Vocabulary("customer", "customers", "visit", "Usual item", "free item",
                    "%s, we've missed you at %3$s",
                    "We'd love to see you again.",
                    "Here's a little something for your next visit:",
                    "Quote this code on your next visit"));

    /**
     * Starting trigger settings.
     *
     * @param typicalCadenceDays a typical customer's gap between visits. Used to
     *                           write the worked examples on the settings screen
     *                           in terms the business recognises; it is not used
     *                           to judge anyone
     * @param minThresholdDays   floor on the flag threshold
     * @param maxThresholdDays   ceiling on it. Must clear one and a half times the
     *                           typical cadence by a wide margin, or the clamp
     *                           flags people before they are due: a ceiling of
     *                           60 days on a 63-day rhythm flags every client
     *                           before they were ever due back
     * @param offerCooldownDays  floor on the quiet period after an offer lands
     * @param offerBudgetWindowDays rolling window the offer cap is counted over
     * @param recoveryWindowDays days after a delivered offer in which a return
     *                           still counts as a return for it; sized to the
     *                           time a customer of this kind takes to rebook
     * @param defaultDealType    the deal the business starts with
     */
    public record Preset(int typicalCadenceDays,
                         int minThresholdDays,
                         int maxThresholdDays,
                         int offerCooldownDays,
                         int recoveryWindowDays,
                         DealType defaultDealType) {

        /** A floor above its ceiling is a typo, not a preset. */
        public Preset {
            if (minThresholdDays < 1 || minThresholdDays > maxThresholdDays) {
                throw new IllegalArgumentException("threshold clamp must satisfy 1 <= min <= max");
            }
        }

        /** The budget window is the same for every type: the cap is what varies by client. */
        public int offerBudgetWindowDays() {
            return 30;
        }
    }

    /**
     * The words used for this kind of business, in the dashboard and in the
     * email its customers receive.
     *
     * @param subjectWithUsual a {@link String#format} pattern taking the
     *                         customer's name, their usual item and the business
     *                         name, in that order
     */
    public record Vocabulary(String customerNoun,
                             String customerNounPlural,
                             String visitNoun,
                             String usualLabel,
                             String freeItemLabel,
                             String subjectWithUsual,
                             String welcomeBackLine,
                             String invitationLine,
                             String codeHint) {
    }

    private final String label;
    private final Preset preset;
    private final Vocabulary vocabulary;

    BusinessType(String label, Preset preset, Vocabulary vocabulary) {
        this.label = label;
        this.preset = preset;
        this.vocabulary = vocabulary;
    }

    public String label() {
        return label;
    }

    public Preset preset() {
        return preset;
    }

    public Vocabulary vocabulary() {
        return vocabulary;
    }

    /** Accepts {@code hair-salon}, {@code HAIR_SALON} and {@code hair salon}. */
    public static BusinessType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("business type is required");
        }
        String normalised = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return valueOf(normalised);
    }
}
