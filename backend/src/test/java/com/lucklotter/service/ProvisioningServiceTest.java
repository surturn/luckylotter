package com.lucklotter.service;

import com.lucklotter.AbstractWebTest;
import com.lucklotter.domain.BusinessType;
import com.lucklotter.domain.DealType;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the operator command line can and cannot create. */
class ProvisioningServiceTest extends AbstractWebTest {

    @Autowired
    private ProvisioningService provisioning;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private AdminUserRepository admins;

    @AfterEach
    void cleanUp() {
        admins.deleteAllInBatch();
        businesses.deleteAllInBatch();
    }

    @Test
    @DisplayName("creates the business and its admin with the settings it was given")
    void createsBusinessAndAdmin() {
        var result = provisioning.createBusiness(request("Owner@Kaldis.test", "kes", "Africa/Nairobi", "15"));

        var business = businesses.findById(result.businessId()).orElseThrow();
        assertThat(business.getName()).isEqualTo("Kaldi Coffee");
        assertThat(business.getCurrencyCode()).isEqualTo("KES");
        assertThat(business.getTimezone()).isEqualTo("Africa/Nairobi");
        assertThat(business.getDefaultDealValue()).isEqualByComparingTo("15");
        assertThat(business.getReplyToEmail()).isEqualTo("hello@kaldis.test");

        var admin = admins.findByEmailIgnoreCase("owner@kaldis.test").orElseThrow();
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(admin.isActive()).isTrue();
        // Stored lower-cased, so the address a human typed in capitals can't
        // become a second, differently-cased account.
        assertThat(result.adminEmail()).isEqualTo("owner@kaldis.test");
    }

    @Test
    @DisplayName("temporary passwords are long, unambiguous and different every time")
    void temporaryPasswordsAreStrong() {
        var first = provisioning.createBusiness(request("a@kaldis.test", "KES", "Africa/Nairobi", "15"));
        var second = provisioning.createBusiness(request("b@kaldis.test", "KES", "Africa/Nairobi", "15"));

        assertThat(first.temporaryPassword()).matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}");
        assertThat(first.temporaryPassword()).isNotEqualTo(second.temporaryPassword());
        assertThat(first.temporaryPassword()).doesNotContain("0", "O", "1", "I", "L");
    }

    @Test
    @DisplayName("an email already in use is refused whatever its case, and nothing is half-created")
    void duplicateEmailIsRefused() {
        provisioning.createBusiness(request("owner@kaldis.test", "KES", "Africa/Nairobi", "15"));

        assertThatThrownBy(() -> provisioning.createBusiness(
                request("OWNER@kaldis.test", "KES", "Africa/Nairobi", "15")))
                .isInstanceOf(ValidationException.class);

        assertThat(businesses.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a deactivated admin's address cannot be reused")
    void deactivatedAddressIsNotRecycled() {
        provisioning.createBusiness(request("owner@kaldis.test", "KES", "Africa/Nairobi", "15"));
        provisioning.deactivateAdmin("owner@kaldis.test");

        assertThatThrownBy(() -> provisioning.createBusiness(
                request("owner@kaldis.test", "KES", "Africa/Nairobi", "15")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("bad currency, timezone, deal value and email are each refused")
    void invalidInputIsRefused() {
        assertThatThrownBy(() -> provisioning.createBusiness(request("a@b.test", "KE", "Africa/Nairobi", "15")))
                .hasMessageContaining("currency");
        assertThatThrownBy(() -> provisioning.createBusiness(request("a@b.test", "KES", "Mars/Olympus", "15")))
                .hasMessageContaining("timezone");
        assertThatThrownBy(() -> provisioning.createBusiness(request("a@b.test", "KES", "Africa/Nairobi", "0")))
                .hasMessageContaining("deal-value");
        assertThatThrownBy(() -> provisioning.createBusiness(request("not-an-email", "KES", "Africa/Nairobi", "15")))
                .hasMessageContaining("admin-email");
        assertThat(businesses.count()).isZero();
    }

    @Test
    @DisplayName("the business type sets the starting settings, so a spa is not given a cafe's 60-day ceiling")
    void typeSeedsTheStartingSettings() {
        var result = provisioning.createBusiness(new ProvisioningService.NewBusiness(
                "Serene Spa", BusinessType.SPA_WELLNESS, "owner@serene.test", "KES", "Africa/Nairobi",
                null, new BigDecimal("15"), null));

        var business = businesses.findById(result.businessId()).orElseThrow();
        assertThat(business.getBusinessType()).isEqualTo(BusinessType.SPA_WELLNESS);
        assertThat(business.getMaxThresholdDays()).isEqualTo(240);
        assertThat(business.getMinThresholdDays()).isEqualTo(21);
        assertThat(business.getRecoveryWindowDays()).isEqualTo(35);
        assertThat(business.getOfferCooldownDays()).isEqualTo(90);
        // The deal value is the business's own decision and is not a preset.
        assertThat(business.getDefaultDealValue()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("a deal type given explicitly beats the preset's")
    void explicitDealTypeWins() {
        var result = provisioning.createBusiness(new ProvisioningService.NewBusiness(
                "Kings", BusinessType.BARBERSHOP, "owner@kings.test", "KES", "Africa/Nairobi",
                DealType.FIXED_AMOUNT_OFF, new BigDecimal("200"), null));

        assertThat(businesses.findById(result.businessId()).orElseThrow().getDefaultDealType())
                .isEqualTo(DealType.FIXED_AMOUNT_OFF);
    }

    @Test
    @DisplayName("a business cannot be created without saying what kind it is")
    void typeIsRequired() {
        assertThatThrownBy(() -> provisioning.createBusiness(new ProvisioningService.NewBusiness(
                "Kings", null, "owner@kings.test", "KES", "Africa/Nairobi",
                null, new BigDecimal("15"), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("type is required")
                .hasMessageContaining("barbershop");
        assertThat(businesses.count()).isZero();
    }

    @Test
    @DisplayName("resetting or deactivating an unknown address says so")
    void unknownAccountIsReported() {
        assertThatThrownBy(() -> provisioning.resetPassword("nobody@kaldis.test"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> provisioning.deactivateAdmin("nobody@kaldis.test"))
                .isInstanceOf(ValidationException.class);
    }

    private static ProvisioningService.NewBusiness request(
            String email, String currency, String timezone, String dealValue) {
        return new ProvisioningService.NewBusiness(
                "Kaldi Coffee", BusinessType.CAFE_RESTAURANT, email, currency, timezone,
                DealType.PERCENT_OFF, new BigDecimal(dealValue), "hello@kaldis.test");
    }
}
