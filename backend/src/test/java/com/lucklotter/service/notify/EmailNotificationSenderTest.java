package com.lucklotter.service.notify;

import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a mail failure is called. The name decides whether the offer is tried
 * again, so a server outage must not be mistaken for a bad address: a bad
 * address is never retried, and one outage would then fail a whole night's
 * batch for good.
 */
class EmailNotificationSenderTest {

    private final JavaMailSender mailer = mock(JavaMailSender.class);
    private final EmailNotificationSender sender =
            new EmailNotificationSender(mailer, "offers@example.test");

    @Test
    @DisplayName("a connection failure is the transport's fault, not a bad address")
    void connectionFailureIsUnavailable() {
        failSendWith(new MailSendException("Mail server connection failed",
                new MessagingException("Could not connect to SMTP host"),
                Map.of(new Object(), new MessagingException("Could not connect to SMTP host"))));

        assertThat(codeOf(send("regular@example.com"))).isEqualTo(OfferFailureCode.SENDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("a read timeout is reported as a timeout")
    void timeoutIsATimeout() {
        failSendWith(new MailSendException("Mail server connection failed",
                new MessagingException("timed out", new SocketTimeoutException("Read timed out"))));

        assertThat(codeOf(send("regular@example.com"))).isEqualTo(OfferFailureCode.SENDER_TIMEOUT);
    }

    @Test
    @DisplayName("a permanent 5xx refusal of the recipient is a bad address")
    void refusedRecipientIsInvalidAddress() {
        failSendWith(recipientRefusal(550));

        assertThat(codeOf(send("regular@example.com"))).isEqualTo(OfferFailureCode.INVALID_EMAIL_ADDRESS);
    }

    @Test
    @DisplayName("a temporary 4xx answer, such as a full mailbox, is not a bad address")
    void temporaryRefusalIsRetriable() {
        failSendWith(recipientRefusal(452));

        assertThat(codeOf(send("regular@example.com"))).isEqualTo(OfferFailureCode.SENDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("an address that does not parse is a bad address and never reaches the server")
    void malformedAddressIsInvalid() {
        assertThat(codeOf(send("<<not an address"))).isEqualTo(OfferFailureCode.INVALID_EMAIL_ADDRESS);
    }

    @Test
    @DisplayName("our own credentials being refused is a rejection, not the customer's fault")
    void authFailureIsRejected() {
        failSendWith(new MailAuthenticationException("535 authentication failed"));

        assertThat(codeOf(send("regular@example.com"))).isEqualTo(OfferFailureCode.SENDER_REJECTED);
    }

    @Test
    @DisplayName("a customer with only a phone number fails without touching the mail server")
    void phoneOnlyCustomerFailsUnavailable() {
        assertThatThrownBy(() -> sender.send(offer(), customer(null)))
                .isInstanceOfSatisfying(NotificationException.class,
                        e -> assertThat(e.getCode()).isEqualTo(OfferFailureCode.SENDER_UNAVAILABLE));
    }

    // --- fixtures -----------------------------------------------------------

    private void failSendWith(RuntimeException failure) {
        when(mailer.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(failure).when(mailer).send(any(MimeMessage.class));
    }

    private static MailSendException recipientRefusal(int smtpCode) {
        var refusal = new SMTPSendFailedException("RCPT TO", smtpCode, "refused", null,
                null, null, new jakarta.mail.Address[]{new jakarta.mail.internet.InternetAddress()});
        return new MailSendException(Map.of(new Object(), refusal));
    }

    private NotificationException send(String email) {
        when(mailer.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        try {
            sender.send(offer(), customer(email));
        } catch (NotificationException e) {
            return e;
        }
        throw new AssertionError("expected the send to fail");
    }

    private static OfferFailureCode codeOf(NotificationException e) {
        return e.getCode();
    }

    private static Offer offer() {
        Business business = new Business();
        business.setName("Kaldi Coffee");
        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setDealType(DealType.PERCENT_OFF);
        offer.setDealValue(new BigDecimal("15"));
        offer.setRedemptionCode("ABCD-EFGH");
        return offer;
    }

    private static Customer customer(String email) {
        Customer customer = new Customer();
        customer.setContactEmail(email);
        customer.setContactPhone("+254700000000");
        return customer;
    }
}
