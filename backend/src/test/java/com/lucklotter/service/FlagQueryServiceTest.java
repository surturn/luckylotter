package com.lucklotter.service;

import com.lucklotter.AbstractPostgresTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.FlagStatus;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OverdueBand;
import com.lucklotter.domain.PosTransaction;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.OfferRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import com.lucklotter.web.dto.FlagDetailResponse;
import com.lucklotter.web.dto.FlagSummaryResponse;
import com.lucklotter.web.dto.PageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The flagged-customer list as an action screen, not a log (§5).
 *
 * <p>The order is the claim being tested. "Who should I act on today" is not
 * "who went quiet most recently", and the difference only shows up in data where
 * the two answers disagree — which is why almost every test here sets up a
 * customer who is recent but small against one who is old but valuable, and
 * asserts which one the screen puts first.
 *
 * <p>The other half is that the ranking is computed over the whole list rather
 * than the page. A test that only ever asks for one page cannot tell the
 * difference between the two, so {@link #pagingSlicesTheRankedList} asks for the
 * second page and expects the global ranking's leftovers, not the second slice
 * of a differently ordered list.
 */
@Import(FlagQueryService.class)
class FlagQueryServiceTest extends AbstractPostgresTest {

    @Autowired
    private FlagQueryService flagQuery;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private RetentionFlagRepository flags;

    @Autowired
    private OfferRepository offers;

    @Autowired
    private PosTransactionRepository transactions;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("priority puts an overdue big spender above a recent small one")
    void priorityIsNotRecency() {
        // The whole point of the screen. B flagged yesterday, A flagged three
        // weeks ago — on "newest first" B leads, and the customer worth three
        // times as much who has been gone a month sits below them.
        Business business = business();
        Customer big = customer(business, "BIG", daysAgo(40), "10.00");
        Customer small = customer(business, "SMALL", daysAgo(11), "10.00");
        // Three customers, so the average is a real number: 900 + 300 + 300.
        Customer filler = customer(business, "FILLER", daysAgo(2), "10.00");
        flag(business, big, FlagStatus.ACTIVE, daysAgo(21));
        flag(business, small, FlagStatus.ACTIVE, daysAgo(1));
        transaction(business, big, new BigDecimal("900.00"), daysAgo(40));
        transaction(business, small, new BigDecimal("300.00"), daysAgo(11));
        transaction(business, filler, new BigDecimal("300.00"), daysAgo(2));
        entityManager.flush();

        List<String> byPriority = refs(list(business.getId(), null, FlagSort.PRIORITY, 0, 25));
        List<String> byRecency = refs(list(business.getId(), null, FlagSort.RECENT, 0, 25));

        assertThat(byPriority)
                .as("the overdue high-value customer is the one worth acting on")
                .containsExactly("BIG", "SMALL");
        assertThat(byRecency)
                .as("the same two customers, and the opposite order")
                .containsExactly("SMALL", "BIG");
    }

    @Test
    @DisplayName("spend is what the customer is worth against this business's own average")
    void valueIsRelativeToTheBusiness() {
        Business business = business();
        Customer big = customer(business, "BIG", daysAgo(12), "10.00");
        Customer small = customer(business, "SMALL", daysAgo(12), "10.00");
        Customer filler = customer(business, "FILLER", daysAgo(12), "10.00");
        flag(business, big, FlagStatus.ACTIVE, daysAgo(2));
        flag(business, small, FlagStatus.ACTIVE, daysAgo(2));
        transaction(business, big, new BigDecimal("1000.00"), daysAgo(12));
        transaction(business, small, new BigDecimal("100.00"), daysAgo(12));
        transaction(business, filler, new BigDecimal("100.00"), daysAgo(12));
        entityManager.flush();

        List<FlagSummaryResponse> items =
                list(business.getId(), null, FlagSort.VALUE, 0, 25).items();

        assertThat(items).extracting(FlagSummaryResponse::customerRef)
                .containsExactly("BIG", "SMALL");
        // 1000 against a mean of 400 is 0.83× the 3×-average ceiling.
        assertThat(items.get(0).priority().value()).isEqualTo(83);
        // Same bar, and 100 clears a quarter of it — the number is a comparison
        // against this business, not a spend figure with a meaning of its own.
        assertThat(items.get(1).priority().value()).isEqualTo(8);
        assertThat(items.get(0).totalSpend()).isEqualByComparingTo("1000.00");
        assertThat(items.get(0).averageTransactionValue()).isEqualByComparingTo("1000.00");
        assertThat(items.get(0).visitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("never having been offered anything scores neutral, not zero")
    void noHistoryIsNeutral() {
        // Not evidence of not responding. Scoring it 0 would bury every new
        // customer under everyone who has ever answered an offer.
        Business business = business();
        Customer untouched = customer(business, "NEW", daysAgo(12), "10.00");
        flag(business, untouched, FlagStatus.ACTIVE, daysAgo(2));
        entityManager.flush();

        FlagSummaryResponse row = only(
                list(business.getId(), null, FlagSort.PRIORITY, 0, 25));

        assertThat(row.priorInterventions()).isZero();
        assertThat(row.priority().history()).isEqualTo(50);
    }

    @Test
    @DisplayName("prior interventions are the customer's other offers, not this flag's own")
    void priorInterventionsExcludeThisFlag() {
        // The row's own offer is the intervention being acted on. Counting it as
        // history would give every customer a 0% response rate on the day their
        // first offer goes out.
        Business business = business();
        Customer customer = customer(business, "POS-1", daysAgo(12), "10.00");
        Instant earlier = daysAgo(40);
        Offer previous = offerFor(business, customer, earlier);
        transaction(business, customer, new BigDecimal("60.00"), earlier.plus(5, ChronoUnit.DAYS));

        RetentionFlag current = flag(business, customer, FlagStatus.ACTIVE, daysAgo(2));
        Offer currentOffer = sendOffer(business, current, daysAgo(2));
        entityManager.flush();

        FlagSummaryResponse row = only(
                list(business.getId(), FlagStatus.ACTIVE, FlagSort.PRIORITY, 0, 25));

        assertThat(previous.getId()).isNotNull();
        assertThat(currentOffer).isNotNull();
        assertThat(row.priorInterventions())
                .as("one earlier offer, and not this flag's own")
                .isEqualTo(1);
        assertThat(row.priorReturns()).isEqualTo(1);
        assertThat(row.priority().history()).isEqualTo(100);
    }

    @Test
    @DisplayName("a return outside the recovery window is not a response")
    void priorReturnUsesTheRecoveryWindow() {
        Business business = business();
        Customer customer = customer(business, "POS-1", daysAgo(12), "10.00");
        Instant earlier = daysAgo(60);
        offerFor(business, customer, earlier);
        transaction(business, customer, new BigDecimal("60.00"), earlier.plus(40, ChronoUnit.DAYS));
        flag(business, customer, FlagStatus.ACTIVE, daysAgo(2));
        entityManager.flush();

        FlagSummaryResponse row = only(
                list(business.getId(), FlagStatus.ACTIVE, FlagSort.PRIORITY, 0, 25));

        assertThat(row.priorInterventions()).isEqualTo(1);
        assertThat(row.priorReturns())
                .as("40 days after a 14-day window is not the offer working")
                .isZero();
        assertThat(row.priority().history()).isZero();
    }

    @Test
    @DisplayName("a resolved flag scores nothing and sorts below the open ones")
    void resolvedFlagsRankLast() {
        Business business = business();
        Customer wonBack = customer(business, "WON", daysAgo(12), "10.00");
        Customer quiet = customer(business, "QUIET", daysAgo(12), "10.00");
        flag(business, wonBack, FlagStatus.RESOLVED, daysAgo(20));
        flag(business, quiet, FlagStatus.ACTIVE, daysAgo(20));
        entityManager.flush();

        List<FlagSummaryResponse> items =
                list(business.getId(), null, FlagSort.PRIORITY, 0, 25).items();

        assertThat(items).extracting(FlagSummaryResponse::customerRef)
                .containsExactly("QUIET", "WON");
        FlagSummaryResponse last = items.get(1);
        assertThat(last.priorityScore()).isZero();
        assertThat(last.daysOverdue()).isNull();
        assertThat(last.riskBand()).isNull();
        assertThat(last.recommendedAction()).isEqualTo("Won back");
    }

    @Test
    @DisplayName("the second page is the ranked list's leftovers, not a second ordering")
    void pagingSlicesTheRankedList() {
        // A page-local sort would pass a single-page assertion and fail here.
        Business business = business();
        Customer top = customer(business, "TOP", daysAgo(90), "10.00");
        Customer middle = customer(business, "MID", daysAgo(30), "10.00");
        Customer bottom = customer(business, "LOW", daysAgo(11), "10.00");
        for (Customer c : List.of(top, middle, bottom)) {
            flag(business, c, FlagStatus.ACTIVE, daysAgo(3));
        }
        entityManager.flush();

        PageResponse<FlagSummaryResponse> first =
                list(business.getId(), null, FlagSort.OVERDUE, 0, 2);
        PageResponse<FlagSummaryResponse> second =
                list(business.getId(), null, FlagSort.OVERDUE, 1, 2);

        assertThat(refs(first)).containsExactly("TOP", "MID");
        assertThat(refs(second)).containsExactly("LOW");
        assertThat(first.totalItems()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("the row says what is blocking it, from the state it is in")
    void recommendedActionNamesTheBlocker() {
        Business business = business();
        Customer unreachable = customer(business, "NO-CONTACT", daysAgo(12), "10.00");
        RetentionFlag unreachableFlag = flag(business, unreachable, FlagStatus.ACTIVE, daysAgo(2));
        Offer blocked = new Offer();
        blocked.setBusiness(business);
        blocked.setFlag(unreachableFlag);
        blocked.setDealType(DealType.PERCENT_OFF);
        blocked.setDealValue(new BigDecimal("25.00"));
        blocked.setRedemptionCode("NC-" + UUID.randomUUID().toString().substring(0, 6));
        blocked.markNoContact();
        offers.saveAndFlush(blocked);

        Customer reached = customer(business, "REACHED", daysAgo(12), "10.00");
        RetentionFlag reachedFlag = flag(business, reached, FlagStatus.ACTIVE, daysAgo(2));
        sendOffer(business, reachedFlag, daysAgo(2));
        entityManager.flush();

        List<FlagSummaryResponse> items =
                list(business.getId(), null, FlagSort.PRIORITY, 0, 25).items();

        assertThat(items).extracting(FlagSummaryResponse::recommendedAction)
                .containsExactlyInAnyOrder(
                        "No way to reach them — add an email or phone",
                        "Offer sent — awaiting their visit");
    }

    @Test
    @DisplayName("the detail view's expected date comes from the visit the flag was raised on")
    void expectedNextVisitIsFromTheVisitTheFlagWasRaisedOn() {
        // Read live, the arithmetic under a flag raised weeks ago moves: the
        // business can retune the cadence, and a customer who came back has a
        // later visit than the one the flag ever saw. The flag has to keep
        // explaining itself with the numbers it actually used.
        Business business = business();
        // Held, not recomputed at the assertion: daysAgo() reads the clock.
        Instant visitBeforeFlag = daysAgo(20);
        Instant sentAt = daysAgo(9);
        Instant cameBackAt = daysAgo(3);
        Customer customer = customer(business, "POS-1", cameBackAt, "10.00");
        RetentionFlag flag = flag(business, customer, FlagStatus.ACTIVE, sentAt);
        flag.setAvgIntervalDaysAtFlag(new BigDecimal("6.00"));
        flag.resolve(cameBackAt);
        flags.saveAndFlush(flag);
        customer.setAvgIntervalDays(new BigDecimal("99.00"));
        customers.saveAndFlush(customer);
        sendOffer(business, flag, sentAt);
        transaction(business, customer, new BigDecimal("42.00"), visitBeforeFlag);
        transaction(business, customer, new BigDecimal("42.00"), cameBackAt);
        entityManager.flush();
        entityManager.clear();

        FlagDetailResponse detail = flagQuery.detail(flag.getId(), business.getId());

        assertThat(detail.expectedNextVisitAt())
                .as("the visit before the flag, plus the cadence snapshotted with it")
                .isEqualTo(visitBeforeFlag.plus(6, ChronoUnit.DAYS));
        assertThat(detail.daysBeyondUsual())
                .as("how late the return was, not how long they have been gone since")
                .isEqualByComparingTo("11.00");
    }

    @Test
    @DisplayName("the risk band is the overview's, measured against the customer's own threshold")
    void riskBandMatchesTheOverviewChart() {
        Business business = business();
        Customer justPast = customer(business, "JUST", daysAgo(11), "10.00");
        Customer longOverdue = customer(business, "LONG", daysAgo(45), "10.00");
        flag(business, justPast, FlagStatus.ACTIVE, daysAgo(1));
        flag(business, longOverdue, FlagStatus.ACTIVE, daysAgo(1));
        entityManager.flush();

        List<FlagSummaryResponse> items =
                list(business.getId(), null, FlagSort.PRIORITY, 0, 25).items();

        assertThat(items).extracting(FlagSummaryResponse::customerRef)
                .containsExactly("LONG", "JUST");
        assertThat(items.get(0).riskBand()).isEqualTo(OverdueBand.LONG_OVERDUE);
        assertThat(items.get(1).riskBand()).isEqualTo(OverdueBand.JUST_PAST);
        assertThat(items.get(0).daysOverdue()).isNotNull();
        assertThat(items.get(0).daysOverdue().doubleValue())
                .as("45 quiet days against a 10-day threshold")
                .isCloseTo(35.0, within(0.01));
    }

    @Test
    @DisplayName("another business's flags, spend and offers never reach this one")
    void tenantScopeHolds() {
        Business mine = business();
        Business theirs = business();
        Customer theirCustomer = customer(theirs, "THEIRS", daysAgo(90), "10.00");
        flag(theirs, theirCustomer, FlagStatus.ACTIVE, daysAgo(2));
        transaction(theirs, theirCustomer, new BigDecimal("9999.00"), daysAgo(90));
        entityManager.flush();

        assertThat(list(mine.getId(), null, FlagSort.PRIORITY, 0, 25).items()).isEmpty();
    }

    @Test
    @DisplayName("the status filter still narrows the ranked list")
    void statusFilterNarrowsTheList() {
        Business business = business();
        Customer open = customer(business, "OPEN", daysAgo(12), "10.00");
        Customer closed = customer(business, "CLOSED", daysAgo(12), "10.00");
        flag(business, open, FlagStatus.ACTIVE, daysAgo(2));
        flag(business, closed, FlagStatus.RESOLVED, daysAgo(20));
        entityManager.flush();

        assertThat(refs(list(business.getId(), FlagStatus.ACTIVE, FlagSort.PRIORITY, 0, 25)))
                .containsExactly("OPEN");
        assertThat(list(business.getId(), FlagStatus.RESOLVED, FlagSort.PRIORITY, 0, 25)
                .totalItems()).isEqualTo(1);
    }

    /**
     * The list as a request sees it — read past the transaction boundary.
     *
     * <p>Production never ranks a page in the same transaction that wrote the
     * flag: the write commits, the request ends, the next read starts clean.
     * A test method is one long transaction, so without the clear the
     * fetch-joined offer resolves to the instance already in the persistence
     * context — the one whose inverse side nobody sets, because the FK lives on
     * the offer — and every row reads as having no offer at all.
     */
    private PageResponse<FlagSummaryResponse> list(UUID businessId, FlagStatus status,
                                                   FlagSort sort, int page, int size) {
        entityManager.flush();
        entityManager.clear();
        return flagQuery.list(businessId, status, sort, page, size);
    }

    @Test
    @DisplayName("the timeline dates the customer's own rhythm, not the threshold it crossed")
    void detailDatesTheUsualRhythm() {
        // §13's "8 days beyond normal" is measured from when the customer was
        // due, not from the later threshold that actually fired the flag. The
        // two differ by the sensitivity setting, and showing the threshold
        // instead would make every flag look minutes old.
        Business business = business();
        // Held, not recomputed at the assertion: daysAgo() reads the clock, and
        // two calls a few milliseconds apart differ by exactly that.
        Instant lastVisit = daysAgo(20);
        Customer customer = customer(business, "POS-1", lastVisit, "6.00");
        RetentionFlag flag = flag(business, customer, FlagStatus.ACTIVE, daysAgo(9));
        transaction(business, customer, new BigDecimal("42.00"), lastVisit);
        entityManager.flush();
        entityManager.clear();

        FlagDetailResponse detail = flagQuery.detail(flag.getId(), business.getId());

        assertThat(detail.expectedNextVisitAt()).isEqualTo(lastVisit.plus(6, ChronoUnit.DAYS));
        assertThat(detail.daysBeyondUsual().doubleValue())
                .as("20 quiet days against a 6-day rhythm")
                .isCloseTo(14.0, within(0.01));
        assertThat(detail.thresholdDaysApplied()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("the timeline shows the visit that answered the offer")
    void detailShowsTheReturnThatAnsweredTheOffer() {
        Business business = business();
        Customer customer = customer(business, "POS-1", daysAgo(12), "10.00");
        RetentionFlag flag = flag(business, customer, FlagStatus.ACTIVE, daysAgo(9));
        sendOffer(business, flag, daysAgo(8));
        Instant returnedAt = daysAgo(5);
        transaction(business, customer, new BigDecimal("42.00"), returnedAt);
        entityManager.flush();
        entityManager.clear();

        FlagDetailResponse detail = flagQuery.detail(flag.getId(), business.getId());

        assertThat(detail.returnedAt()).isEqualTo(returnedAt);
        assertThat(detail.returnAmount()).isEqualByComparingTo("42.00");
    }

    @Test
    @DisplayName("a visit outside the recovery window is not shown as the offer working")
    void detailIgnoresAReturnOutsideTheWindow() {
        // The same predicate the list counts prior returns with: a customer the
        // list says ignored the offer cannot have a page showing one.
        Business business = business();
        Customer customer = customer(business, "POS-1", daysAgo(70), "10.00");
        RetentionFlag flag = flag(business, customer, FlagStatus.ACTIVE, daysAgo(65));
        Instant sentAt = daysAgo(60);
        sendOffer(business, flag, sentAt);
        // 40 days after a 14-day window.
        transaction(business, customer, new BigDecimal("42.00"), daysAgo(20));
        entityManager.flush();
        entityManager.clear();

        FlagDetailResponse detail = flagQuery.detail(flag.getId(), business.getId());

        assertThat(detail.offerSentAt()).isEqualTo(sentAt);
        assertThat(detail.returnedAt()).isNull();
        assertThat(detail.returnAmount()).isNull();
    }

    @Test
    @DisplayName("a flag with no offer has no return, and dates the visit it was raised on")
    void detailWithoutAnOffer() {
        Business business = business();
        Instant lastVisit = daysAgo(12);
        Customer customer = customer(business, "POS-1", lastVisit, "10.00");
        RetentionFlag flag = flag(business, customer, FlagStatus.ACTIVE, daysAgo(9));
        transaction(business, customer, new BigDecimal("42.00"), lastVisit);
        transaction(business, customer, new BigDecimal("42.00"), daysAgo(5));
        entityManager.flush();
        entityManager.clear();

        FlagDetailResponse detail = flagQuery.detail(flag.getId(), business.getId());

        assertThat(detail.returnedAt())
                .as("a visit with no offer in front of it is not a response to one")
                .isNull();
        assertThat(detail.expectedNextVisitAt())
                .as("the visit after the flag does not move a date the flag already used")
                .isEqualTo(lastVisit.plus(10, ChronoUnit.DAYS));
    }

    private List<String> refs(PageResponse<FlagSummaryResponse> page) {
        return page.items().stream().map(FlagSummaryResponse::customerRef).toList();
    }

    private static FlagSummaryResponse only(PageResponse<FlagSummaryResponse> page) {
        assertThat(page.items()).hasSize(1);
        return page.items().getFirst();
    }

    private Business business() {
        Business business = new Business();
        business.setName("Test Café " + UUID.randomUUID());
        business.setCurrencyCode("KES");
        business.setDefaultDealType(DealType.PERCENT_OFF);
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return businesses.saveAndFlush(business);
    }

    private Customer customer(Business business, String ref, Instant lastVisit, String cadence) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        // Plain refs: each test runs in its own rolled-back transaction
        // against its own business, so there is nothing to collide with, and
        // the assertions read as the customers they are about.
        customer.setExternalRef(ref);
        customer.setAvgIntervalDays(new BigDecimal(cadence));
        customer.setTransactionCount(6);
        customer.setLastVisitAt(lastVisit);
        return customers.saveAndFlush(customer);
    }

    private RetentionFlag flag(Business business, Customer customer, FlagStatus status,
                               Instant flaggedAt) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(new BigDecimal("10.00"));
        flag.setFlaggedAt(flaggedAt);
        if (status == FlagStatus.RESOLVED) {
            // resolve() rather than setStatus(): the table's check constraint
            // wants a resolved_at whenever the status says resolved, and every
            // real close of a flag carries one.
            flag.resolve(flaggedAt.plus(2, ChronoUnit.DAYS));
        }
        return flags.saveAndFlush(flag);
    }

    /** A delivered offer against an already-saved flag. */
    private Offer sendOffer(Business business, RetentionFlag flag, Instant sentAt) {
        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(business.getDefaultDealValue());
        offer.setRedemptionCode("SENT-" + UUID.randomUUID().toString().substring(0, 6));
        offer.markSent(sentAt);
        return offers.saveAndFlush(offer);
    }

    /** A flag of its own plus a delivered offer, for building a customer's past. */
    private Offer offerFor(Business business, Customer customer, Instant sentAt) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(new BigDecimal("10.00"));
        flag.setFlaggedAt(sentAt.minus(5, ChronoUnit.MINUTES));
        // Closed rather than left open: the customer came back, and only one
        // ACTIVE flag may exist per customer, so a past intervention whose
        // customer returned cannot still be holding the slot.
        flag.resolve(sentAt.plus(5, ChronoUnit.DAYS));
        flags.saveAndFlush(flag);

        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(business.getDefaultDealType());
        offer.setDealValue(business.getDefaultDealValue());
        offer.setRedemptionCode("PAST-" + UUID.randomUUID().toString().substring(0, 6));
        offer.markSent(sentAt);
        return offers.saveAndFlush(offer);
    }

    private void transaction(Business business, Customer customer, BigDecimal amount,
                            Instant occurredAt) {
        PosTransaction transaction = new PosTransaction();
        transaction.setBusiness(business);
        transaction.setCustomer(customer);
        transaction.setExternalTxnId("TXN-" + UUID.randomUUID());
        transaction.setAmount(amount);
        transaction.setOccurredAt(occurredAt);
        transactions.save(transaction);
    }

    /**
     * Truncated to milliseconds so a value survives the round trip: Postgres
     * keeps microseconds, so a nanosecond-precision Instant comes back a
     * fraction different and an exact assertion on a stored timestamp would
     * fail for a reason that has nothing to do with the code under test.
     */
    private static Instant daysAgo(int days) {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(days, ChronoUnit.DAYS);
    }
}
