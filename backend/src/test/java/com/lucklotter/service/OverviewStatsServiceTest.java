package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.PosTransaction;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.OverviewStatsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overview's internal consistency, which is the thing a reader notices
 * first and trusts last.
 *
 * <p>The screen used to show a status breakdown that mixed flag counts with
 * customer counts, so its parts did not add up to anything — an operator who
 * tried to reconcile the dashboard against itself found it couldn't be done, and
 * the reasonable conclusion from there is that none of the numbers are solid.
 * These tests hold the partition together.
 */
@Import({OverviewStatsService.class, RetentionImpactService.class})
class OverviewStatsServiceTest extends AbstractPostgresTest {

    @Autowired
    private OverviewStatsService overview;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private RetentionFlagRepository flags;

    @Autowired
    private PosTransactionRepository transactions;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("the status breakdown is a partition of the customer base")
    void breakdownAddsUpToTheCustomerBase() {
        Business business = business();
        driftingCustomer(business);
        returnedCustomer(business);
        onRhythmCustomer(business);
        customerWithoutCadence(business);
        entityManager.flush();

        OverviewStatsResponse response = overview.forBusiness(business.getId());
        OverviewStatsResponse.StatusBreakdown breakdown = response.statusBreakdown();

        assertThat(breakdown.drifting()).isEqualTo(1);
        assertThat(breakdown.returned()).isEqualTo(1);
        assertThat(breakdown.onRhythm()).isEqualTo(1);
        assertThat(breakdown.notEnoughData()).isEqualTo(1);
        assertThat(breakdown.total())
                .as("the four buckets are disjoint and cover everyone")
                .isEqualTo(4);
        assertThat(breakdown.drifting() + breakdown.returned()
                + breakdown.onRhythm() + breakdown.notEnoughData())
                .isEqualTo(breakdown.total());
        assertThat(response.customersTotal()).isEqualTo(4);
        assertThat(response.customersMonitored()).isEqualTo(3);
        assertThat(response.customersBelowThreshold()).isEqualTo(1);
    }

    @Test
    @DisplayName("a customer who came back and lapsed again counts as drifting, not returned")
    void returnedCustomerWhoLapsedAgainIsDrifting() {
        // Both statements are true of them, and only one of them tells an
        // operator what to do this morning.
        Business business = business();
        Customer customer = returnedCustomer(business);
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("7.00"));
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        flags.saveAndFlush(flag);
        entityManager.flush();

        OverviewStatsResponse.StatusBreakdown breakdown =
                overview.forBusiness(business.getId()).statusBreakdown();

        assertThat(breakdown.drifting()).isEqualTo(1);
        assertThat(breakdown.returned()).isZero();
    }

    @Test
    @DisplayName("high-value drifting customers are measured against the business's own average")
    void highValueIsRelativeToTheBusiness() {
        // Three customers, and the thresholds why. The bar is twice the mean of
        // customer totals, so with only two customers it can never be cleared:
        // one holding all the spend puts the mean at half that spend, and twice
        // the mean is the spend itself. The rule needs a customer base to mean
        // anything, which is why the seeder ships one.
        Business business = business();
        Customer big = driftingCustomer(business);
        Customer small = driftingCustomer(business);
        Customer alsoSmall = driftingCustomer(business);
        transaction(business, big, new BigDecimal("900.00"));
        transaction(business, small, new BigDecimal("100.00"));
        transaction(business, alsoSmall, new BigDecimal("100.00"));
        entityManager.flush();

        assertThat(overview.forBusiness(business.getId()).highValueDrifting()).isEqualTo(1);
    }

    @Test
    @DisplayName("with too few customers to have an average, nothing is high value")
    void highValueNeedsACustomerBase() {
        // Not a bug to paper over: the alternative is a fixed amount, which is
        // meaningless across a coffee shop and a restaurant.
        Business business = business();
        Customer only = driftingCustomer(business);
        transaction(business, only, new BigDecimal("9000.00"));
        entityManager.flush();

        assertThat(overview.forBusiness(business.getId()).highValueDrifting()).isZero();
    }

    @Test
    @DisplayName("every card on the overview carries its own definition")
    void definitionsTravelWithTheNumbers() {
        Business business = business();
        entityManager.flush();

        OverviewStatsResponse.Definitions definitions =
                overview.forBusiness(business.getId()).definitions();

        // Not decoration: a rate whose denominator only an engineer can supply is
        // one nobody can act on or defend.
        assertThat(definitions.recovery()).contains("offer was actually delivered");
        assertThat(definitions.customersBelowThreshold()).contains("fewer than three");
        assertThat(definitions.statusBreakdown()).contains("add up");
    }

    @Test
    @DisplayName("nothing delivered yet means no recovery rate, not a rate of zero")
    void noCohortMeansNoRate() {
        Business business = business();
        entityManager.flush();

        OverviewStatsResponse.Recovery recovery = overview.forBusiness(business.getId()).recovery();

        assertThat(recovery.contacted()).isZero();
        assertThat(recovery.percent()).isNull();
        assertThat(recovery.windowDays()).isEqualTo(14);
    }

    private Business business() {
        Business business = new Business();
        business.setName("Test Café " + UUID.randomUUID());
        business.setDefaultDealType(DealType.PERCENT_OFF);
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return businesses.saveAndFlush(business);
    }

    private Customer driftingCustomer(Business business) {
        Customer customer = customer(business);
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("7.00"));
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        flags.saveAndFlush(flag);
        return customer;
    }

    private Customer returnedCustomer(Business business) {
        Customer customer = customer(business);
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("7.00"));
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        flag.setStatus(FlagStatus.RESOLVED);
        flag.setResolvedAt(Instant.now().minus(3, ChronoUnit.DAYS));
        flags.saveAndFlush(flag);
        return customer;
    }

    private Customer onRhythmCustomer(Business business) {
        return customer(business);
    }

    private Customer customerWithoutCadence(Business business) {
        Customer customer = customer(business);
        customer.setAvgIntervalDays(null);
        return customers.saveAndFlush(customer);
    }

    private Customer customer(Business business) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("POS-" + UUID.randomUUID());
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setTransactionCount(6);
        customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return customers.saveAndFlush(customer);
    }

    private void transaction(Business business, Customer customer, BigDecimal amount) {
        PosTransaction transaction = new PosTransaction();
        transaction.setBusiness(business);
        transaction.setCustomer(customer);
        transaction.setExternalTxnId("TXN-" + UUID.randomUUID());
        transaction.setAmount(amount);
        transaction.setOccurredAt(Instant.now().minus(5, ChronoUnit.DAYS));
        transactions.save(transaction);
    }
}
