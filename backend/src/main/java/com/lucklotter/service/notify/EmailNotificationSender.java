package com.lucklotter.service.notify;

import com.lucklotter.domain.Customer;
import com.lucklotter.domain.DealType;
import com.lucklotter.domain.Offer;
import com.lucklotter.domain.OfferFailureCode;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Delivers offers by email (FR-5).
 *
 * <p>Takes precedence over {@link LoggingNotificationSender} when
 * {@code lucklotter.notifications.email.enabled} is true; the stub remains the
 * default so a deployment without mail configured still runs.
 *
 * <p>A customer with a phone number but no email is a <em>failure</em> here,
 * not a silent success: this sender has no SMS channel, so claiming the offer
 * was sent would mean recording a delivery that never happened. It fails with
 * a retriable code, so wiring an SMS sender later picks those offers back up.
 */
@Component
@Primary
@ConditionalOnProperty(name = "lucklotter.notifications.email.enabled", havingValue = "true")
public class EmailNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationSender.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailNotificationSender(JavaMailSender mailSender,
                                   @Value("${lucklotter.notifications.email.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(Offer offer, Customer customer) {
        String recipient = customer.getContactEmail();
        if (recipient == null || recipient.isBlank()) {
            throw new NotificationException(
                    OfferFailureCode.SENDER_UNAVAILABLE,
                    "Customer has a phone number but no email, and no SMS channel is configured");
        }

        String businessName = offer.getBusiness().getName();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(recipient);
            helper.setSubject(subject(customer, businessName));
            helper.setText(body(offer, customer, businessName), true);
            mailSender.send(message);
        } catch (MessagingException | MailAuthenticationException e) {
            throw new NotificationException(OfferFailureCode.SENDER_REJECTED,
                    "SMTP rejected the message: " + e.getMessage(), e);
        } catch (MailSendException e) {
            // Usually a bad address or a refused recipient. Retriable, because
            // ingesting a corrected address should let the next attempt work.
            throw new NotificationException(OfferFailureCode.INVALID_EMAIL_ADDRESS,
                    "SMTP could not deliver to the stored address: " + e.getMessage(), e);
        } catch (MailException e) {
            throw new NotificationException(OfferFailureCode.SENDER_UNAVAILABLE,
                    "Mail transport unavailable: " + e.getMessage(), e);
        }

        // The address is deliberately absent — an offer ID is enough to trace
        // this, and a log of customer email addresses is exactly what NFR-4
        // forbids.
        log.info("Offer email accepted by SMTP: offerId={} customerId={}",
                offer.getId(), customer.getId());
    }

    /**
     * Personalised when a name is on file, generic when it isn't — never
     * "Hi ," which is the failure mode that makes an automated mail obvious.
     *
     * <p>The usual order goes in the subject when we know it: "your matcha is
     * waiting" is recognisably about this customer in an inbox preview, where
     * "we've missed you" is recognisably a mailshot.
     */
    private String subject(Customer customer, String businessName) {
        String name = customer.greetingName();
        String usual = customer.getUsualItem();

        if (name != null && usual != null) {
            return name + ", your " + usual + " is waiting at " + businessName;
        }
        if (name != null) {
            return name + ", we've missed you at " + businessName;
        }
        return "We've missed you at " + businessName;
    }

    /**
     * How long they've been away, in words a person would use.
     *
     * <p>Rounded deliberately. "It's been 23 days" is accurate and slightly
     * unsettling — it reads as surveillance. "A few weeks" says the same thing
     * in the register a barista would use, and is just as true.
     *
     * <p>Returns null when the dates aren't there to support a claim, in which
     * case the sentence is dropped rather than padded with a guess.
     */
    private String awayFor(Offer offer, Customer customer) {
        Instant lastVisit = customer.getLastVisitAt();
        Instant flaggedAt = offer.getFlag() == null ? null : offer.getFlag().getFlaggedAt();
        if (lastVisit == null || flaggedAt == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(lastVisit, flaggedAt);
        if (days < 10) {
            return "a little while";
        }
        if (days < 21) {
            return "a couple of weeks";
        }
        if (days < 45) {
            return "a few weeks";
        }
        return "a while";
    }

    /**
     * Inline styles and a table-free layout, because email clients discard
     * stylesheets and disagree about everything else.
     */
    private String body(Offer offer, Customer customer, String businessName) {
        String code = offer.getRedemptionCode() == null ? "" : offer.getRedemptionCode();
        String name = customer.greetingName();

        // Rendered as its own paragraph, or omitted entirely. Everything
        // interpolated into this HTML is escaped: a customer name arrives from a
        // POS export and is not trusted markup.
        String greeting = "<p style=\"font-size:16px;line-height:1.5;margin:0 0 16px;\">"
                + (name == null ? "Hello," : "Hi " + escape(name) + ",")
                + "</p>";

        // "It's been a few weeks since your usual black matcha tea." Each clause
        // is dropped independently when the fact behind it is missing, so the
        // sentence degrades to "It's been a while since we saw you" rather than
        // asserting something we don't know.
        String usual = customer.getUsualItem();
        String away = awayFor(offer, customer);
        String missedYou = "It's been " + (away == null ? "a while" : away)
                + (usual == null
                        ? " since we saw you at <strong>" + escape(businessName) + "</strong>."
                        : " since your usual <strong>" + escape(usual) + "</strong> at <strong>"
                          + escape(businessName) + "</strong>.");

        // Only when we actually know the order — this is the line that carries
        // the warmth, and inventing it would be the one thing that destroys it.
        String usualLine = usual == null ? "" : """
              <p style="font-size:16px;line-height:1.5;margin:0 0 16px;">
                We've still got it on the menu.
              </p>
            """;

        return """
            <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
                        max-width:520px;margin:0 auto;padding:24px;color:#0f172a;">
              %s
              <p style="font-size:16px;line-height:1.5;margin:0 0 16px;">
                %s
              </p>
              %s
              <!-- Deliberately not "the next one's on us": the deal is whatever
                   the business configured, and a percentage off is not a free
                   drink. The card below states the actual offer. -->
              <p style="font-size:16px;line-height:1.5;margin:0 0 24px;">
                Here's a little something for when you're next passing:
              </p>
              <div style="border:1px solid #dbeafe;border-radius:12px;padding:20px;
                          background:#f8fafc;text-align:center;">
                <div style="font-size:24px;font-weight:700;margin-bottom:8px;">%s</div>
                <div style="font-size:13px;color:#52627a;margin-bottom:12px;">
                  Quote this code on your next visit
                </div>
                <div style="font-family:monospace;font-size:20px;letter-spacing:2px;
                            font-weight:600;">%s</div>
              </div>
              <p style="font-size:13px;color:#52627a;line-height:1.5;margin:24px 0 0;">
                See you soon — the team at %s.
              </p>
            </div>
            """.formatted(greeting, missedYou, usualLine, dealDescription(offer),
                          escape(code), escape(businessName));
    }

    /**
     * Minimal HTML escaping for values interpolated into the template above.
     *
     * <p>A customer name comes from a POS export, and a business name from admin
     * input; neither is trusted markup. Without this, an apostrophe-heavy trading
     * name is merely ugly but an angle bracket breaks the layout outright.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String dealDescription(Offer offer) {
        BigDecimal value = offer.getDealValue();
        DealType type = offer.getDealType();
        return switch (type) {
            case PERCENT_OFF -> value.stripTrailingZeros().toPlainString() + "% off";
            case FIXED_AMOUNT_OFF -> "KES " + value.stripTrailingZeros().toPlainString() + " off";
            case FREE_ITEM -> value.stripTrailingZeros().toPlainString() + " item on us";
        };
    }
}
