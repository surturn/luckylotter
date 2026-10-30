package com.lucklotter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The presets are the product's answer to "does this work for a spa?", so they
 * are held to the property that matters: a customer on their usual rhythm must
 * not be flagged. That fails when the ceiling on the flag threshold sits below
 * the rhythm itself.
 */
class BusinessTypeTest {

    @ParameterizedTest
    @EnumSource(BusinessType.class)
    @DisplayName("a typical customer on their usual rhythm is never flagged")
    void ceilingClearsTheTypicalRhythm(BusinessType type) {
        Business business = new Business();
        business.applyPreset(type);
        BusinessType.Preset preset = type.preset();

        // Threshold for a typical customer, unclamped and as the trigger applies it.
        java.math.BigDecimal threshold = business.thresholdDaysFor(
                java.math.BigDecimal.valueOf(preset.typicalCadenceDays()));

        // Past one full rhythm with room to spare, so ordinary lateness is not a
        // lapse. A ceiling at or below the rhythm would flag everyone before they
        // were due back, every cycle.
        assertThat(threshold.doubleValue())
                .as("%s: threshold for a %d-day rhythm", type, preset.typicalCadenceDays())
                .isGreaterThan(preset.typicalCadenceDays() * 1.25);
    }

    @ParameterizedTest
    @EnumSource(BusinessType.class)
    @DisplayName("every preset passes the same bounds the settings screen enforces")
    void presetsAreWithinTheSettingsBounds(BusinessType type) {
        BusinessType.Preset preset = type.preset();

        assertThat(preset.minThresholdDays()).isBetween(1, 365);
        assertThat(preset.maxThresholdDays()).isBetween(preset.minThresholdDays(), 365);
        assertThat(preset.offerCooldownDays()).isBetween(0, 365);
        assertThat(preset.offerBudgetWindowDays()).isBetween(1, 365);
        assertThat(preset.recoveryWindowDays()).isBetween(1, 365);
    }

    @ParameterizedTest
    @EnumSource(BusinessType.class)
    @DisplayName("the recovery window is long enough to credit a return, and sized to the rhythm")
    void recoveryWindowFitsTheRhythm(BusinessType type) {
        BusinessType.Preset preset = type.preset();

        // Two weeks is the least that credits a return at all: a window of a few
        // days on a seven-week rhythm would count almost none. And it should not
        // outrun the rhythm itself, or a customer who was coming back anyway is
        // credited to the offer; twice the typical gap is the most the cafe, the
        // shortest rhythm, needs.
        assertThat(preset.recoveryWindowDays()).isGreaterThanOrEqualTo(14);
        assertThat(preset.recoveryWindowDays()).isLessThanOrEqualTo(preset.typicalCadenceDays() * 2);
    }

    @ParameterizedTest
    @EnumSource(BusinessType.class)
    @DisplayName("every type has wording, and the subject pattern takes name, usual item and business")
    void vocabularyIsComplete(BusinessType type) {
        BusinessType.Vocabulary words = type.vocabulary();

        assertThat(words.customerNoun()).isNotBlank();
        assertThat(words.customerNounPlural()).isNotBlank();
        assertThat(words.visitNoun()).isNotBlank();
        assertThat(words.usualLabel()).isNotBlank();
        assertThat(words.freeItemLabel()).isNotBlank();
        assertThat(words.welcomeBackLine()).isNotBlank();
        assertThat(words.invitationLine()).isNotBlank();
        assertThat(words.codeHint()).isNotBlank();
        assertThat(words.subjectWithUsual().formatted("Sam", "fade", "Kings"))
                .contains("Sam").contains("Kings");
    }

    @Test
    @DisplayName("the cafe preset is exactly what the product shipped with, so existing businesses are unchanged")
    void cafePresetMatchesTheOriginalDefaults() {
        Business shipped = new Business();
        Business preset = new Business();
        preset.applyPreset(BusinessType.CAFE_RESTAURANT);

        assertThat(preset.getMinThresholdDays()).isEqualTo(shipped.getMinThresholdDays());
        assertThat(preset.getMaxThresholdDays()).isEqualTo(shipped.getMaxThresholdDays());
        assertThat(preset.getOfferCooldownDays()).isEqualTo(shipped.getOfferCooldownDays());
        assertThat(preset.getOfferBudgetWindowDays()).isEqualTo(shipped.getOfferBudgetWindowDays());
        assertThat(preset.getRecoveryWindowDays()).isEqualTo(shipped.getRecoveryWindowDays());
        assertThat(preset.getDefaultDealType()).isEqualTo(shipped.getDefaultDealType());
    }

    @Test
    @DisplayName("applying a preset leaves the sensitivity, cap and deal value alone")
    void presetDoesNotTouchTheBusinessesOwnChoices() {
        Business business = new Business();
        business.setSensitivityMultiplier(new java.math.BigDecimal("2.00"));
        business.setOfferCapPerWindow(40);
        business.setDefaultDealValue(new java.math.BigDecimal("12.00"));

        business.applyPreset(BusinessType.SPA_WELLNESS);

        assertThat(business.getSensitivityMultiplier()).isEqualByComparingTo("2.00");
        assertThat(business.getOfferCapPerWindow()).isEqualTo(40);
        assertThat(business.getDefaultDealValue()).isEqualByComparingTo("12.00");
        assertThat(business.getMaxThresholdDays()).isEqualTo(240);
    }

    @Test
    @DisplayName("a type can be given as the operator would type it")
    void parsingIsForgiving() {
        assertThat(BusinessType.parse("hair-salon")).isEqualTo(BusinessType.HAIR_SALON);
        assertThat(BusinessType.parse("Hair Salon")).isEqualTo(BusinessType.HAIR_SALON);
        assertThat(BusinessType.parse("SPA_WELLNESS")).isEqualTo(BusinessType.SPA_WELLNESS);
        assertThatThrownBy(() -> BusinessType.parse("florist")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BusinessType.parse(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
