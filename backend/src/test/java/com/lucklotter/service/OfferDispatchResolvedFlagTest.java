package com.lucklotter.service;

import com.lucklotter.AbstractWebTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An offer is a message about a lapse. Once the customer has come back and their
 * flag is resolved, sending it is wrong, and the retry backlog is exactly where
 * that happens: an offer that failed earlier can still be waiting when the
 * customer walks in.
 */
class OfferDispatchResolvedFlagTest extends AbstractWebTest {

    @Autowired
    private OfferDispatchService dispatch;

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
        offers.deleteAllInBatch();
        flags.deleteAllInBatch();
        customers.deleteAllInBatch();
        businesses.deleteAllInBatch();
    }

    @Test
    @DisplayName("an offer whose customer already came back is not sent, and one still lapsed is")
    void resolvedFlagIsNotSent() {
        Business business = businesses.save(business());
        Offer stillLapsed = pendingOffer(business, false);
        Offer cameBack = pendingOffer(business, true);

        dispatch.dispatchSendable(business.getId());

        assertThat(offers.findById(stillLapsed.getId()).orElseThrow().getStatus())
                .isEqualTo(OfferStatus.SENT);
        Offer untouched = offers.findById(cameBack.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(untouched.getSentAt()).isNull();
    }

    @Test
    @DisplayName("sending a single offer directly refuses a resolved flag too")
    void directSendRefusesResolvedFlag(@Autowired OfferSendService sender) {
        Business business = businesses.save(business());
        Offer cameBack = pendingOffer(business, true);

        assertThat(sender.send(cameBack.getId())).isFalse();
        assertThat(offers.findById(cameBack.getId()).orElseThrow().getStatus())
                .isEqualTo(OfferStatus.PENDING);
    }

    private Business business() {
        Business business = new Business();
        business.setName("Kaldi Coffee");
        business.setDefaultDealValue(new BigDecimal("15.00"));
        return business;
    }

    private Offer pendingOffer(Business business, boolean flagResolved) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("cust-" + UUID.randomUUID());
        customer.setContactEmail("regular@example.com");
        customer.setTransactionCount(5);
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
        customers.save(customer);

        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        if (flagResolved) {
            flag.resolve(Instant.now());
        }
        flags.save(flag);

        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(DealType.PERCENT_OFF);
        offer.setDealValue(new BigDecimal("15.00"));
        return offers.saveAndFlush(offer);
    }
}
