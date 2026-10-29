package com.lucklotter.service;

import com.lucklotter.AbstractWebTest;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import com.lucklotter.domain.OfferRetryPolicy;
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
 * The nightly scan retries failed offers. These pin which ones it picks up: a
 * transient failure with attempts left, yes; a dead address or an offer that has
 * used its attempts, no. The sender here always succeeds, so an offer that moves
 * to SENT was selected and one left at FAILED was passed over.
 */
class OfferRetryDispatchTest extends AbstractWebTest {

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
    @DisplayName("a transient failure with attempts left is retried")
    void transientFailureIsRetried() {
        Business business = businesses.save(business());
        Offer offer = failedOffer(business, OfferFailureCode.SENDER_UNAVAILABLE, 1);

        dispatch.dispatchSendable(business.getId());

        Offer reloaded = offers.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OfferStatus.SENT);
        assertThat(reloaded.getSendAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("an offer that has used all its attempts is left alone")
    void exhaustedOfferIsNotRetried() {
        Business business = businesses.save(business());
        Offer offer = failedOffer(business, OfferFailureCode.SENDER_UNAVAILABLE,
                OfferRetryPolicy.MAX_SEND_ATTEMPTS);

        dispatch.dispatchSendable(business.getId());

        Offer reloaded = offers.findById(offer.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OfferStatus.FAILED);
        assertThat(reloaded.getSendAttempts()).isEqualTo(OfferRetryPolicy.MAX_SEND_ATTEMPTS);
    }

    @Test
    @DisplayName("a bad address is not mailed again, whatever the attempt count")
    void badAddressIsNotRetried() {
        Business business = businesses.save(business());
        Offer email = failedOffer(business, OfferFailureCode.INVALID_EMAIL_ADDRESS, 1);
        Offer phone = failedOffer(business, OfferFailureCode.INVALID_PHONE_NUMBER, 1);

        dispatch.dispatchSendable(business.getId());

        assertThat(offers.findById(email.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.FAILED);
        assertThat(offers.findById(phone.getId()).orElseThrow().getStatus()).isEqualTo(OfferStatus.FAILED);
    }

    // --- fixtures -----------------------------------------------------------

    private Business business() {
        Business business = new Business();
        business.setName("Kaldi Coffee");
        business.setDefaultDealValue(new BigDecimal("15.00"));
        return business;
    }

    private Offer failedOffer(Business business, OfferFailureCode code, int attempts) {
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
        flags.save(flag);

        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setFlag(flag);
        offer.setDealType(DealType.PERCENT_OFF);
        offer.setDealValue(new BigDecimal("15.00"));
        for (int i = 0; i < attempts; i++) {
            offer.markFailed(code);
        }
        return offers.saveAndFlush(offer);
    }
}
