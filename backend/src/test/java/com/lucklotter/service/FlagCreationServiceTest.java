package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferStatus;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of {@link FlagCreationService} — the seam where the
 * cooldown (FR-4), the budget ceiling (FR-4) and contactability routing (FR-5)
 * all decide what happens to one customer. Each rule is unit-tested in
 * isolation elsewhere; nothing until now exercised them together in the order
 * the service applies them.
 *
 * <p><strong>Deliberately not transactional.</strong> {@code @DataJpaTest}
 * normally wraps each test in a transaction and rolls it back, but
 * {@code flagAndGenerateOffer} is {@code REQUIRES_NEW}: it would suspend that
 * transaction and open its own, which could not see fixtures the test had not
 * committed. A test written the usual way would fail on a "business not found"
 * that never happens in production. So the test transaction is switched off and
 * fixtures are committed for real, which is also the only way the pessimistic
 * lock in {@code findByIdForUpdate} runs against anything meaningful. The cost
 * is that cleanup is manual — see {@link #cleanUp()}.
 */
@Import({FlagCreationService.class, RedemptionCodeGenerator.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FlagCreationServiceTest extends AbstractPostgresTest {

    private static final BigDecimal THRESHOLD_APPLIED = new BigDecimal("10.50");

    @Autowired
    private FlagCreationService flagCreation;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private RetentionFlagRepository flags;

    @Autowired
    private OfferRepository offers;

    @AfterEach
    void cleanUp() {
        // Order matters: offers reference flags, flags reference customers and
        // businesses. Nothing rolls back for us here.
        offers.deleteAllInBatch();
        flags.deleteAllInBatch();
        customers.deleteAllInBatch();
        businesses.deleteAllInBatch();
    }

    @Test
    @DisplayName("a customer inside their cooldown is not re-flagged")
    void cooldownSuppressesReFlagging() {
        // The loop this closes: go quiet, collect a discount, return, go quiet
        // again. A resolved flag makes the customer immediately re-flaggable,
        // so without the cooldown the scan re-offers on the next pass.
        Business business = business();
        Customer customer = customer(business);
        deliveredOffer(business, customer, Instant.now().minus(5, ChronoUnit.DAYS));

        Optional<UUID> offerId = flag(customer, business);

        assertThat(offerId).isEmpty();
        assertThat(flags.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("an offer that was never delivered does not start a cooldown")
    void undeliveredOfferDoesNotSuppress() {
        // Anchoring on generation rather than delivery would silently stop
        // flagging a customer the business has never actually managed to
        // contact — hiding the very coverage gap NO_CONTACT exists to expose.
        Business business = business();
        Customer customer = customer(business);
        Offer undelivered = offer(business, resolvedFlag(business, customer));
        undelivered.markNoContact();
        offers.save(undelivered);

        Optional<UUID> offerId = flag(customer, business);

        assertThat(offerId).isPresent();
    }

    @Test
    @DisplayName("a customer past their cooldown is flagged again")
    void cooldownExpiresAndAllowsReFlagging() {
        // 7-day cadence x 3.0 multiplier = 21 days, above the 30-day floor's
        // reach, so the floor is what governs here; 40 days clears both.
        Business business = business();
        Customer customer = customer(business);
        deliveredOffer(business, customer, Instant.now().minus(40, ChronoUnit.DAYS));

        assertThat(flag(customer, business)).isPresent();
    }

    @Test
    @DisplayName("a customer with no contact details gets an offer at NO_CONTACT")
    void uncontactableCustomerIsVisibleNotPending() {
        // Never silently PENDING: an offer stuck pending forever reads as a
        // delivery that hasn't happened yet rather than one that never can.
        Business business = business();
        Customer customer = customer(business);
        customer.setContactEmail(null);
        customer.setContactPhone(null);
        customers.save(customer);

        UUID offerId = flag(customer, business).orElseThrow();

        Offer created = offers.findById(offerId).orElseThrow();
        assertThat(created.getStatus()).isEqualTo(OfferStatus.NO_CONTACT);
        assertThat(created.getSentAt()).isNull();
    }

    @Test
    @DisplayName("at the budget cap the flag is still created, with the offer suppressed")
    void budgetCapSuppressesTheOfferButNotTheFlag() {
        // Skipping the flag would tell the admin their retention is healthy
        // when what actually ran out was their spend.
        Business business = business();
        business.setOfferCapPerWindow(1);
        businesses.save(business);
        // One offer already committed this window fills the cap of 1.
        Customer spender = customer(business);
        offers.save(offer(business, resolvedFlag(business, spender)));

        Customer customer = customer(business);
        UUID offerId = flag(customer, business).orElseThrow();

        Offer created = offers.findById(offerId).orElseThrow();
        assertThat(created.getStatus()).isEqualTo(OfferStatus.SUPPRESSED_BUDGET);
        // No failure_code: nothing failed, so it must not enter the retry
        // backlog. The CHECK constraint agrees, but this is the behaviour.
        assertThat(created.getFailureCode()).isNull();
        assertThat(flags.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("an uncontactable customer at the cap stays NO_CONTACT")
    void contactabilityIsDecidedBeforeBudget() {
        // Order matters: an offer that can never reach anyone is not spend, so
        // reporting it as suppressed-for-budget would both overstate the cost
        // of the programme and hide the missing contact details.
        Business business = business();
        business.setOfferCapPerWindow(0);
        businesses.save(business);
        Customer customer = customer(business);
        customer.setContactEmail(null);
        customer.setContactPhone(null);
        customers.save(customer);

        UUID offerId = flag(customer, business).orElseThrow();

        assertThat(offers.findById(offerId).orElseThrow().getStatus())
                .isEqualTo(OfferStatus.NO_CONTACT);
    }

    @Test
    @DisplayName("flag and offer commit together — never a flag on its own")
    void flagAndOfferCommitAsOneUnit() {
        // A flag with no offer shows in the dashboard as a customer the system
        // noticed and then did nothing about.
        Business business = business();
        Customer customer = customer(business);

        UUID offerId = flag(customer, business).orElseThrow();

        List<RetentionFlag> persisted = flags.findAll();
        assertThat(persisted).hasSize(1);
        Offer created = offers.findById(offerId).orElseThrow();
        assertThat(created.getFlag().getId()).isEqualTo(persisted.get(0).getId());
        assertThat(created.getRedemptionCode()).isNotBlank();
        assertThat(created.getStatus()).isEqualTo(OfferStatus.PENDING);
    }

    @Test
    @DisplayName("a customer already carrying an open flag is not flagged twice")
    void alreadyFlaggedCustomerIsSkipped() {
        Business business = business();
        Customer customer = customer(business);
        RetentionFlag open = new RetentionFlag();
        open.setBusiness(business);
        open.setCustomer(customer);
        open.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        open.setThresholdDaysApplied(THRESHOLD_APPLIED);
        flags.save(open);

        assertThat(flag(customer, business)).isEmpty();
        assertThat(flags.findAll()).hasSize(1);
    }

    // --- fixtures -----------------------------------------------------------

    private Optional<UUID> flag(Customer customer, Business business) {
        return flagCreation.flagAndGenerateOffer(
                customer.getId(), business.getId(), THRESHOLD_APPLIED);
    }

    private Business business() {
        Business business = new Business();
        business.setName("Test Café");
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return businesses.save(business);
    }

    private Customer customer(Business business) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("cust-" + UUID.randomUUID());
        customer.setContactEmail("regular@example.com");
        customer.setTransactionCount(3);
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return customers.save(customer);
    }

    private RetentionFlag resolvedFlag(Business business, Customer customer) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(THRESHOLD_APPLIED);
        // Resolved, so the partial unique index leaves the customer flaggable —
        // which is exactly the state the cooldown has to cover.
        flag.setStatus(FlagStatus.RESOLVED);
        flag.setResolvedAt(Instant.now());
        return flags.save(flag);
    }

    private Offer offer(Business business, RetentionFlag flag) {
        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(business.getDefaultDealValue());
        offer.setRedemptionCode("TEST-" + UUID.randomUUID().toString().substring(0, 4));
        return offer;
    }

    private void deliveredOffer(Business business, Customer customer, Instant sentAt) {
        Offer offer = offer(business, resolvedFlag(business, customer));
        offer.markSent(sentAt);
        offers.save(offer);
    }
}
