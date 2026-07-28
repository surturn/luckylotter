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
            helper.setSubject("We've missed you at " + businessName);
            helper.setText(body(offer, businessName), true);
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
     * Inline styles and a table-free layout, because email clients discard
     * stylesheets and disagree about everything else.
     */
    private String body(Offer offer, String businessName) {
        String code = offer.getRedemptionCode() == null ? "" : offer.getRedemptionCode();
        return """
            <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
                        max-width:520px;margin:0 auto;padding:24px;color:#0f172a;">
              <p style="font-size:16px;line-height:1.5;margin:0 0 16px;">
                It's been a while since we saw you at <strong>%s</strong>.
              </p>
              <p style="font-size:16px;line-height:1.5;margin:0 0 24px;">
                Here's something to bring you back:
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
            """.formatted(businessName, dealDescription(offer), code, businessName);
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
