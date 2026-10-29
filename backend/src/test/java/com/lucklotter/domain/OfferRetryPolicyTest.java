package com.lucklotter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which failed offers a later scan will try again. The aim is that a transient
 * fault heals itself, and a dead address is not mailed every night.
 */
class OfferRetryPolicyTest {

    @Test
    @DisplayName("a queued offer is sendable and a delivered one is not")
    void queuedIsSendable() {
        Offer offer = new Offer();

        assertThat(offer.isSendable()).isTrue();

        offer.markSent(Instant.now());
        assertThat(offer.isSendable()).isFalse();
        assertThat(offer.getSendAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a transient failure is retried until the attempts run out")
    void transientFailureIsCapped() {
        Offer offer = new Offer();

        for (int attempt = 1; attempt < OfferRetryPolicy.MAX_SEND_ATTEMPTS; attempt++) {
            offer.markFailed(OfferFailureCode.SENDER_UNAVAILABLE);
            assertThat(offer.isRetriable()).as("after attempt %d", attempt).isTrue();
        }

        offer.markFailed(OfferFailureCode.SENDER_UNAVAILABLE);
        assertThat(offer.getSendAttempts()).isEqualTo(OfferRetryPolicy.MAX_SEND_ATTEMPTS);
        assertThat(offer.isRetriable()).isFalse();
        assertThat(offer.isSendable()).isFalse();
    }

    @Test
    @DisplayName("a bad address is not retried even on the first failure")
    void badAddressIsNeverRetried() {
        Offer offer = new Offer();

        offer.markFailed(OfferFailureCode.INVALID_EMAIL_ADDRESS);

        assertThat(offer.isRetriable()).isFalse();
        assertThat(offer.isSendable()).isFalse();
    }

    @Test
    @DisplayName("a corrected contact detail gives the offer a fresh start")
    void reopeningResetsTheCount() {
        Offer offer = new Offer();
        offer.markFailed(OfferFailureCode.INVALID_EMAIL_ADDRESS);

        offer.reopenForRetry();

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.PENDING);
        assertThat(offer.getFailureCode()).isNull();
        assertThat(offer.getSendAttempts()).isZero();
        assertThat(offer.isSendable()).isTrue();
    }

    @Test
    @DisplayName("only offers still waiting on delivery count as unsent")
    void unsentMeansPendingOrFailed() {
        Offer pending = new Offer();
        Offer failed = new Offer();
        failed.markFailed(OfferFailureCode.SENDER_TIMEOUT);
        Offer sent = new Offer();
        sent.markSent(Instant.now());
        Offer noContact = new Offer();
        noContact.markNoContact();

        assertThat(pending.isUnsent()).isTrue();
        assertThat(failed.isUnsent()).isTrue();
        assertThat(sent.isUnsent()).isFalse();
        assertThat(noContact.isUnsent()).isFalse();
    }

    @Test
    @DisplayName("closing an offer because the customer returned leaves no failure code")
    void returnedOfferCarriesNoFailure() {
        Offer offer = new Offer();
        offer.markFailed(OfferFailureCode.SENDER_UNAVAILABLE);

        offer.markSuppressedReturned();

        assertThat(offer.getStatus()).isEqualTo(OfferStatus.SUPPRESSED_RETURNED);
        assertThat(offer.getFailureCode()).isNull();
        assertThat(offer.isSendable()).isFalse();
    }
}
