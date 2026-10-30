package com.lucklotter.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which column of an uploaded CSV holds which of our fields (FR-1).
 *
 * <p>Values are the header names as they appear in the file. A null means "the
 * admin didn't map this one", which is an error for a required field and simply
 * absent for an optional one.
 */
public record ColumnMapping(
    String customerRef,
    String customerName,
    String usualItem,
    String externalTxnId,
    String amount,
    String occurredAt,
    String contactEmail,
    String contactPhone,
    /**
     * Optional. A booking system exports cancelled and no-show appointments
     * alongside the real ones; when this column is mapped, rows whose value is
     * one of {@link #SKIPPED_STATUSES} are left out instead of being counted as
     * visits.
     */
    String status
) {

    /** Our field names, in the order the mapping form should present them. */
    public static final String CUSTOMER_REF = "customerRef";
    public static final String CUSTOMER_NAME = "customerName";
    public static final String USUAL_ITEM = "usualItem";
    public static final String EXTERNAL_TXN_ID = "externalTxnId";
    public static final String AMOUNT = "amount";
    public static final String OCCURRED_AT = "occurredAt";
    public static final String CONTACT_EMAIL = "contactEmail";
    public static final String CONTACT_PHONE = "contactPhone";
    public static final String STATUS = "status";

    /**
     * Statuses that mean the customer did not actually come, compared with
     * everything but letters stripped, so "No-show", "no_show" and "NO SHOW" are
     * one value. A booking export is full of these, and counting a no-show as a
     * visit would move the client's rhythm by the very thing the product exists
     * to notice: that they did not turn up.
     */
    public static final Set<String> SKIPPED_STATUSES = Set.of(
            "cancelled", "canceled", "noshow", "declined", "void", "voided",
            "refunded", "rejected", "failed", "deleted", "abandoned");

    public static final List<String> REQUIRED_FIELDS =
            List.of(CUSTOMER_REF, EXTERNAL_TXN_ID, AMOUNT, OCCURRED_AT);

    /**
     * Header spellings we recognise per field, normalised to lowercase letters
     * only. Drawn from what tills actually emit — the point is to get the
     * common cases pre-filled, not to be exhaustive; anything unrecognised is
     * mapped by hand.
     */
    private static final Map<String, List<String>> SYNONYMS = Map.of(
        CUSTOMER_REF, List.of("customerref", "customerid", "customercode", "customer", "custid",
                              "clientid", "clientref", "clientnumber", "clientcode", "guestid",
                              "memberid", "member", "loyaltyid", "cardnumber", "cardno"),
        // Deliberately excludes a bare "customer", which CUSTOMER_REF claims:
        // in a till export that column is far more often the ID than the name,
        // and suggesting one header for two fields would be worse than leaving
        // the name unmapped for the admin to set.
        CUSTOMER_NAME, List.of("customername", "name", "fullname", "firstname", "givenname",
                               "clientname", "membername", "contactname"),
        USUAL_ITEM, List.of("usualitem", "usualorder", "usual", "regularorder", "favouriteitem",
                            "favoriteitem", "favourite", "favorite", "preferreditem", "item",
                            "product", "drink",
                            // Booking systems: what was done, not what was ordered.
                            "service", "services", "servicename", "treatment", "treatmentname",
                            "usualservice", "usualtreatment"),
        EXTERNAL_TXN_ID, List.of("externaltxnid", "transactionid", "txnid", "transactionref",
                                 "receiptno", "receiptnumber", "receipt", "invoiceno", "invoicenumber",
                                 "invoice", "ordernumber", "orderid", "orderno", "saleid", "reference",
                                 "appointmentid", "appointmentno", "appointmentnumber", "bookingid",
                                 "bookingref", "bookingreference", "bookingnumber", "visitid"),
        AMOUNT, List.of("amount", "total", "totalamount", "grandtotal", "nettotal", "netamount",
                        "value", "price", "amountpaid", "paid", "servicetotal", "amountcharged",
                        "charged", "revenue"),
        OCCURRED_AT, List.of("occurredat", "date", "datetime", "transactiondate", "transactiontime",
                             "timestamp", "saledate", "purchasedate", "time", "createdat",
                             "appointmentdate", "appointmenttime", "appointmentdatetime", "bookingdate",
                             "start", "starttime", "startdate", "visitdate", "scheduledat"),
        CONTACT_EMAIL, List.of("contactemail", "email", "emailaddress", "customeremail", "mail"),
        CONTACT_PHONE, List.of("contactphone", "phone", "phonenumber", "mobile", "mobilenumber",
                               "msisdn", "telephone", "tel", "customerphone", "cell", "cellphone",
                               "clientphone", "whatsapp"),
        STATUS, List.of("status", "appointmentstatus", "bookingstatus", "visitstatus", "state")
    );

    /** True when every required field has a column assigned. */
    public boolean isComplete() {
        return customerRef != null && externalTxnId != null && amount != null && occurredAt != null;
    }

    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(CUSTOMER_REF, customerRef);
        map.put(CUSTOMER_NAME, customerName);
        map.put(USUAL_ITEM, usualItem);
        map.put(EXTERNAL_TXN_ID, externalTxnId);
        map.put(AMOUNT, amount);
        map.put(OCCURRED_AT, occurredAt);
        map.put(CONTACT_EMAIL, contactEmail);
        map.put(CONTACT_PHONE, contactPhone);
        map.put(STATUS, status);
        return map;
    }

    /**
     * Best guess at a mapping for the given headers.
     *
     * <p>A suggestion only — it is returned to the admin to confirm or change,
     * never applied on its own. Guessing wrong about which column is the
     * transaction ID would silently corrupt idempotency, so a human confirms.
     */
    public static ColumnMapping suggestFor(List<String> headers) {
        return new ColumnMapping(
                match(headers, CUSTOMER_REF),
                match(headers, CUSTOMER_NAME),
                match(headers, USUAL_ITEM),
                match(headers, EXTERNAL_TXN_ID),
                match(headers, AMOUNT),
                match(headers, OCCURRED_AT),
                match(headers, CONTACT_EMAIL),
                match(headers, CONTACT_PHONE),
                match(headers, STATUS));
    }

    /** True when the row's status says the customer did not come. */
    public static boolean isSkippedStatus(String value) {
        return value != null && SKIPPED_STATUSES.contains(normalise(value));
    }

    private static String match(List<String> headers, String field) {
        List<String> synonyms = SYNONYMS.get(field);
        for (String synonym : synonyms) {
            for (String header : headers) {
                if (normalise(header).equals(synonym)) {
                    return header;
                }
            }
        }
        return null;
    }

    /** "Cust ID" and "cust_id" are the same header as far as matching goes. */
    static String normalise(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
