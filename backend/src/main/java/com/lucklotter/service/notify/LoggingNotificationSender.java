package com.lucklotter.service.notify;

import com.lucklotter.domain.Customer;
import com.lucklotter.domain.Offer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Phase 1 sender (§6, §12): records that an offer would have gone out, and
 * to which channel, without contacting anything.
 *
 * <p>SMS costs money per message and Phase 1 has no budget, so "delivery" here
 * means a log line. It logs internal IDs and the channel it <em>would</em> have
 * used — never the email address or phone number itself (NFR-4).
 *
 * <p>A real sender added later should be annotated {@code @Primary} so it wins
 * injection over this one.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Offer offer, Customer customer) {
        String channel = customer.getContactEmail() != null && !customer.getContactEmail().isBlank()
                ? "EMAIL"
                : "SMS";
        log.info("Offer delivery (stub): offerId={} customerId={} businessId={} channel={} deal={}/{}",
                offer.getId(), customer.getId(), offer.getBusiness().getId(),
                channel, offer.getDealType(), offer.getDealValue());
    }
}
