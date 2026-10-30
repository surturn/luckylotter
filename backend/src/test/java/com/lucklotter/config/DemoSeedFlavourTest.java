package com.lucklotter.config;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.BusinessType;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.service.FlagCreationService;
import com.lucklotter.service.IngestionService;
import com.lucklotter.service.OfferDispatchService;
import com.lucklotter.service.OfferSendService;
import com.lucklotter.service.RedemptionCodeGenerator;
import com.lucklotter.service.RetentionScanService;
import com.lucklotter.service.notify.NotificationSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every demo flavour has to be a believable business, not a cafe with a different
 * name over it.
 *
 * <p>A flavour stretches the cast's rhythms and spends and takes its clamps from
 * the type's preset. The way that goes wrong quietly is a rhythm longer than the
 * ceiling on the flag threshold: every customer then flags before they are due
 * back, and the demo shows a retention problem that is really a configuration
 * one. So each flavour is seeded for real and asked the questions a prospect
 * would: who flagged, who didn't, and is the flagged share plausible.
 *
 * <p>Not transactional, for the reason given on {@link DemoSeedDispatchTest}.
 */
@Import({DemoSeedFlavourTest.Config.class, IngestionService.class,
        RetentionScanService.class, FlagCreationService.class, OfferDispatchService.class,
        OfferSendService.class, RedemptionCodeGenerator.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoSeedFlavourTest extends AbstractPostgresTest {

    private static final Set<String> QUIET_AND_FLAGGABLE =
            Set.of("POS-1001", "POS-1002", "POS-1003", "POS-1004", "POS-1005", "POS-1006");

    private static final Set<String> ON_RHYTHM_OR_TOO_NEW =
            Set.of("POS-2001", "POS-2002", "POS-2003", "POS-3001", "POS-3002");

    @Autowired
    private BusinessRepository businesses;
    @Autowired
    private AdminUserRepository adminUsers;
    @Autowired
    private CustomerRepository customers;
    @Autowired
    private PosTransactionRepository transactions;
    @Autowired
    private RetentionFlagRepository flags;
    @Autowired
    private OfferRepository offers;
    @Autowired
    private IngestionService ingestion;
    @Autowired
    private RetentionScanService retentionScan;
    @Autowired
    private RedemptionCodeGenerator redemptionCodes;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanUp() {
        offers.deleteAllInBatch();
        flags.deleteAllInBatch();
        transactions.deleteAllInBatch();
        customers.deleteAllInBatch();
        adminUsers.deleteAllInBatch();
        businesses.deleteAllInBatch();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = BusinessType.class,
            names = {"CAFE_RESTAURANT", "BARBERSHOP", "HAIR_SALON", "SPA_WELLNESS"})
    void flavourSeedsABelievableBusiness(BusinessType type) {
        cleanUp();
        new DemoDataSeeder(businesses, adminUsers, customers, flags, offers, ingestion,
                retentionScan, redemptionCodes, passwordEncoder,
                "seed-admin@lucklotter.test", "seed-password", type.name()).run();

        Business business = businesses.findAll().get(0);

        // Takes the preset's settings, so the demo shows what a real client of
        // this type would be given.
        BusinessType.Preset preset = type.preset();
        assertThat(business.getBusinessType()).isEqualTo(type);
        assertThat(business.getMaxThresholdDays()).isEqualTo(preset.maxThresholdDays());
        assertThat(business.getRecoveryWindowDays()).isEqualTo(preset.recoveryWindowDays());

        // The named cast still behaves as written: quiet ones flag, the rest don't.
        for (String ref : QUIET_AND_FLAGGABLE) {
            assertThat(hasOpenFlag(business, ref)).as("%s flagged for %s", ref, type).isTrue();
        }
        for (String ref : ON_RHYTHM_OR_TOO_NEW) {
            assertThat(hasOpenFlag(business, ref)).as("%s not flagged for %s", ref, type).isFalse();
        }

        // A plausible share of the base is flagged: some, but nowhere near all.
        // The failure this guards against is every customer flagging at once.
        long customerCount = customers.count();
        long open = flags.findAll().stream().filter(f -> f.getStatus() == FlagStatus.ACTIVE).count();
        assertThat(open).as("open flags of %d customers for %s", customerCount, type)
                .isBetween(customerCount / 50, customerCount / 3);

        // The cap is sized for the fixture's backlog, so nothing is held back for
        // budget before anyone has configured anything.
        List<Offer> all = offers.findAll();
        assertThat(all).noneMatch(o -> o.getStatus() == OfferStatus.SUPPRESSED_BUDGET);

        // The first named customer has a usual item in this trade's words, which
        // is what makes the offer email in the inbox read as the right business.
        Customer first = customers.findByBusinessIdAndExternalRef(business.getId(), "POS-1001").orElseThrow();
        assertThat(first.getUsualItem()).isNotBlank();
    }

    private boolean hasOpenFlag(Business business, String ref) {
        Customer customer = customers.findByBusinessIdAndExternalRef(business.getId(), ref).orElseThrow();
        return flags.findByCustomerIdAndStatus(customer.getId(), FlagStatus.ACTIVE).isPresent();
    }

    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        NotificationSender discardingSender() {
            return (offer, customer) -> { };
        }

        @Bean
        @SuppressWarnings("deprecation")
        PasswordEncoder testPasswordEncoder() {
            return NoOpPasswordEncoder.getInstance();
        }
    }
}
