package com.lucklotter.service.notify;

import com.lucklotter.domain.Business;
import com.lucklotter.domain.BusinessType;
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

    // --- the wording follows the kind of business --------------------------------

    @Test
    @DisplayName("a salon is written to in the language of a salon, not a cafe")
    void salonCopySpeaksForASalon() throws Exception {
        MimeMessage sent = deliver(offerFor(BusinessType.HAIR_SALON, DealType.PERCENT_OFF, "20"),
                customer("regular@example.com", "Wanjiru", "silk press"));

        String body = html(sent);
        assertThat(sent.getSubject()).isEqualTo("Wanjiru, time for your silk press at Zuri Hair Studio?");
        assertThat(body).contains("since your usual <strong>silk press</strong>");
        assertThat(body).contains("We'd love to have you back in the chair.");
        assertThat(body).contains("Mention this code when you book");
        assertThat(body).contains("Here's a little something for your next booking:");
        // None of the cafe's wording leaks across.
        assertThat(body).doesNotContain("menu").doesNotContain("next passing")
                .doesNotContain("Quote this code");
    }

    @Test
    @DisplayName("a cafe still gets exactly the wording it always had")
    void cafeCopyIsUnchanged() throws Exception {
        MimeMessage sent = deliver(offerFor(BusinessType.CAFE_RESTAURANT, DealType.PERCENT_OFF, "25"),
                customer("regular@example.com", "Aisha", "flat white"));

        String body = html(sent);
        assertThat(sent.getSubject()).isEqualTo("Aisha, your flat white is waiting at Zuri Hair Studio");
        assertThat(body).contains("We've still got it on the menu.");
        assertThat(body).contains("Here's a little something for when you're next passing:");
        assertThat(body).contains("Quote this code on your next visit");
    }

    @Test
    @DisplayName("the welcome-back line is only there when the usual item is known")
    void welcomeBackNeedsAUsualItem() throws Exception {
        MimeMessage sent = deliver(offerFor(BusinessType.SPA_WELLNESS, DealType.PERCENT_OFF, "15"),
                customer("regular@example.com", null, null));

        assertThat(html(sent)).doesNotContain("welcome you back").contains("Hello,");
        assertThat(sent.getSubject()).isEqualTo("We've missed you at Zuri Hair Studio");
    }

    @Test
    @DisplayName("a fixed amount off is written in the business's own currency")
    void fixedAmountUsesTheBusinessCurrency() throws Exception {
        Offer offer = offerFor(BusinessType.BARBERSHOP, DealType.FIXED_AMOUNT_OFF, "5.00");
        offer.getBusiness().setCurrencyCode("GBP");

        assertThat(html(deliver(offer, customer("a@b.test", "Sam", "fade")))).contains("GBP 5 off");
    }

    @Test
    @DisplayName("a free item reads as an add-on for a service business, and as an item for a cafe")
    void freeItemReadsForTheTrade() throws Exception {
        assertThat(html(deliver(offerFor(BusinessType.SPA_WELLNESS, DealType.FREE_ITEM, "1"),
                customer("a@b.test", "Sam", "facial")))).contains("A free add-on");
        assertThat(html(deliver(offerFor(BusinessType.BARBERSHOP, DealType.FREE_ITEM, "2"),
                customer("a@b.test", "Sam", "fade")))).contains("2 free add-ons");
        assertThat(html(deliver(offerFor(BusinessType.CAFE_RESTAURANT, DealType.FREE_ITEM, "1"),
                customer("a@b.test", "Sam", "latte")))).contains("A free item");
    }

    // --- fixtures -----------------------------------------------------------

    /** Sends through a mailer that accepts everything, and returns what it was handed. */
    private MimeMessage deliver(Offer offer, Customer customer) {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailer.createMimeMessage()).thenReturn(message);
        sender.send(offer, customer);
        return message;
    }

    private static String html(MimeMessage message) throws Exception {
        return message.getContent().toString();
    }

    private static Offer offerFor(BusinessType type, DealType deal, String value) {
        Business business = new Business();
        business.setName("Zuri Hair Studio");
        business.applyPreset(type);
        Offer offer = new Offer();
        offer.setBusiness(business);
        offer.setDealType(deal);
        offer.setDealValue(new BigDecimal(value));
        offer.setRedemptionCode("ABCD-EFGH");
        return offer;
    }

    private static Customer customer(String email, String name, String usual) {
        Customer customer = new Customer();
        customer.setContactEmail(email);
        customer.setName(name);
        customer.setUsualItem(usual);
        return customer;
    }

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
