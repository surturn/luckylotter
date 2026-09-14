package com.lucklotter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucklotter.AbstractWebTest;
import com.lucklotter.domain.AdminUser;
import com.lucklotter.domain.Business;
import com.lucklotter.domain.Customer;
import com.lucklotter.domain.RetentionFlag;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.repo.CustomerRepository;
import com.lucklotter.repo.PosTransactionRepository;
import com.lucklotter.repo.RetentionFlagRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP tier: authentication, the tenant boundary, and the contracts the
 * dashboard depends on.
 *
 * <p>This layer had no coverage at all until now, and that is where the
 * 404-answering-500 bug survived — every other test sits at or below the
 * service layer, where the filter chain and exception handler don't exist. The
 * cases here are the ones a service test structurally cannot reach.
 */
class ApiSecurityAndContractTest extends AbstractWebTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private AdminUserRepository admins;

    @Autowired
    private CustomerRepository customers;

    @Autowired
    private RetentionFlagRepository flags;

    @Autowired
    private PosTransactionRepository transactions;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanUp() {
        transactions.deleteAllInBatch();
        flags.deleteAllInBatch();
        customers.deleteAllInBatch();
        admins.deleteAllInBatch();
        businesses.deleteAllInBatch();
    }

    // --- authentication -----------------------------------------------------

    @Test
    @DisplayName("no token is 401, not a redirect or a 403")
    void unauthenticatedIsRejected() throws Exception {
        // An API client has to be able to tell "you are not logged in" from
        // "you are logged in but may not have this".
        mvc.perform(get("/v1/flags")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/businesses/me/config")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a garbage token is rejected rather than trusted")
    void malformedTokenIsRejected() throws Exception {
        mvc.perform(get("/v1/flags").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("wrong password is refused without revealing which half was wrong")
    void badCredentialsAreRejected() throws Exception {
        Business business = business("Kaldi's");
        admin(business, "owner@kaldis.test");

        mvc.perform(login("owner@kaldis.test", "wrong-password"))
                .andExpect(status().isUnauthorized());
        // Same answer for an account that doesn't exist: a different status or
        // message would let anyone enumerate which addresses are registered.
        mvc.perform(login("nobody@kaldis.test", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a valid login returns a token that actually opens the API")
    void loginYieldsAWorkingToken() throws Exception {
        Business business = business("Kaldi's");
        admin(business, "owner@kaldis.test");

        String token = tokenFor("owner@kaldis.test");

        mvc.perform(get("/v1/flags").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // --- tenant boundary (NFR-1) --------------------------------------------

    @Test
    @DisplayName("one business cannot read another's flag")
    void flagsAreScopedToTheCallersTenant() throws Exception {
        Business mine = business("Kaldi's");
        admin(mine, "owner@kaldis.test");
        Business theirs = business("Blue Bottle");
        RetentionFlag theirFlag = flag(theirs, customer(theirs));

        String token = tokenFor("owner@kaldis.test");

        // Not 200-with-empty-body and not 403: the flag must be indistinguishable
        // from one that does not exist, or the response confirms it does.
        mvc.perform(get("/v1/flags/" + theirFlag.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the flag list shows only the caller's own tenant")
    void flagListIsScopedToTheCallersTenant() throws Exception {
        Business mine = business("Kaldi's");
        admin(mine, "owner@kaldis.test");
        RetentionFlag myFlag = flag(mine, customer(mine));
        Business theirs = business("Blue Bottle");
        flag(theirs, customer(theirs));

        String body = mvc.perform(get("/v1/flags")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode items = json.readTree(body).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("flagId").asText()).isEqualTo(myFlag.getId().toString());
    }

    @Test
    @DisplayName("the overview reports over the window it was asked for")
    void overviewHonoursTheReportingWindow() throws Exception {
        Business mine = business("Kaldi's");
        admin(mine, "owner@kaldis.test");

        String body = mvc.perform(get("/v1/stats/overview").param("window", "LAST_7_DAYS")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = json.readTree(body);
        assertThat(response.get("reportingPeriod").get("key").asText()).isEqualTo("LAST_7_DAYS");
        assertThat(response.get("reportingPeriod").get("unit").asText()).isEqualTo("day");
        // One point per bucket, so the caller never has to be told how many to
        // expect from somewhere else.
        assertThat(response.get("activitySeries")).hasSize(7);
    }

    @Test
    @DisplayName("an unknown reporting window is a 400 that names the parameter")
    void unknownReportingWindowIsABadRequest() throws Exception {
        Business mine = business("Kaldi's");
        admin(mine, "owner@kaldis.test");

        // Not a 500, and not a silent fall back to the default. A dashboard that
        // quietly answered a different period than the one asked for would show
        // a number under the wrong heading, which is worse than showing none.
        mvc.perform(get("/v1/stats/overview").param("window", "LAST_FORTNIGHT")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("window")));
    }

    @Test
    @DisplayName("the diagnostic comes back whole, with its sections and its working")
    void diagnosticCarriesEverySection() throws Exception {
        Business mine = business("Kaldi's");
        admin(mine, "owner@kaldis.test");
        Customer customer = customer(mine);
        flag(mine, customer);

        String body = mvc.perform(get("/v1/stats/diagnostic")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode report = json.readTree(body);
        // §7's five sections, none of them absent: a report that silently drops
        // the one it could not build leaves the reader to guess which.
        assertThat(report.get("executiveSummary")).isNotNull();
        assertThat(report.get("customerBehaviour").get("cadenceSegments")).hasSize(5);
        assertThat(report.get("customerBehaviour").get("purchaseCountDistribution")).hasSize(5);
        assertThat(report.get("atRiskCustomers")).hasSize(1);
        assertThat(report.get("interventionPerformance").get("impact")).isNotNull();
        assertThat(report.get("opportunity").get("available").asBoolean()).isFalse();
        assertThat(report.get("opportunity").get("method").asText()).contains("control group");
    }

    @Test
    @DisplayName("the diagnostic reports the caller's own business and nobody else's")
    void diagnosticIsScopedToTheCallersTenant() throws Exception {
        Business mine = business("Kaldi's");
        admin(mine, "owner@kaldis.test");
        flag(mine, customer(mine));
        Business theirs = business("Blue Bottle");
        flag(theirs, customer(theirs));
        flag(theirs, customer(theirs));

        String body = mvc.perform(get("/v1/stats/diagnostic")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The count a leak would move first.
        assertThat(json.readTree(body).get("executiveSummary")
                .get("customersShowingCadenceDisruption").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("naming another tenant in the body is refused, not quietly rewritten")
    void ingestRefusesAMismatchedBusinessId() throws Exception {
        // The most dangerous shape of this bug is a caller writing a transaction
        // into someone else's tenant just by naming it in the payload. The API
        // treats the body's businessId as an assertion to check rather than a
        // scope to honour, and refuses a mismatch outright (NFR-1). Refusing
        // beats silently substituting the token's tenant: a client sending the
        // wrong ID has a bug, and writing the row anyway hides it.
        Business mine = business("Kaldi's");
        admin(mine, "owner@kaldis.test");
        Business theirs = business("Blue Bottle");

        String payload = """
                {"businessId":"%s","customerRef":"POS-1","externalTxnId":"txn-1",
                 "amount":9.50,"occurredAt":"%s"}
                """.formatted(theirs.getId(), Instant.now().toString());

        mvc.perform(post("/v1/transactions")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        // What actually matters: the write landed in neither tenant.
        assertThat(customers.findByBusinessIdAndExternalRef(theirs.getId(), "POS-1")).isEmpty();
        assertThat(customers.findByBusinessIdAndExternalRef(mine.getId(), "POS-1")).isEmpty();
    }

    @Test
    @DisplayName("ingest into the caller's own tenant succeeds")
    void ingestIntoOwnTenantSucceeds() throws Exception {
        // The other half: the check must not be so strict that the legitimate
        // call fails too.
        Business mine = business("Kaldi's");
        admin(mine, "owner@kaldis.test");

        String payload = """
                {"businessId":"%s","customerRef":"POS-1","externalTxnId":"txn-1",
                 "amount":9.50,"occurredAt":"%s"}
                """.formatted(mine.getId(), Instant.now().toString());

        mvc.perform(post("/v1/transactions")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is2xxSuccessful());

        assertThat(customers.findByBusinessIdAndExternalRef(mine.getId(), "POS-1")).isPresent();
    }

    // --- contracts ----------------------------------------------------------

    @Test
    @DisplayName("an unknown route answers 404, not 500")
    void unknownRouteIsNotFound() throws Exception {
        // Regression: NoResourceFoundException fell through to the catch-all
        // handler, so a mistyped path reported a server fault and filled the
        // error log with routine 404s.
        mvc.perform(get("/v1/nonexistent")
                        .header("Authorization", "Bearer " + anyToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("an invalid body answers 400 with the offending fields named")
    void invalidBodyIsBadRequestWithFieldErrors() throws Exception {
        Business business = business("Kaldi's");
        admin(business, "owner@kaldis.test");

        // Missing customerRef and a negative amount.
        String payload = """
                {"externalTxnId":"txn-1","amount":-5.00,"occurredAt":"%s"}
                """.formatted(Instant.now().toString());

        mvc.perform(post("/v1/transactions")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.customerRef").exists())
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
    }

    @Test
    @DisplayName("an error body never echoes the caller's data back")
    void errorBodiesDoNotEchoInput() throws Exception {
        Business business = business("Kaldi's");
        admin(business, "owner@kaldis.test");

        String payload = """
                {"customerRef":"POS-1","externalTxnId":"txn-1","amount":-5.00,
                 "occurredAt":"%s","contactEmail":"regular@example.com"}
                """.formatted(Instant.now().toString());

        String body = mvc.perform(post("/v1/transactions")
                        .header("Authorization", "Bearer " + tokenFor("owner@kaldis.test"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // A validation message that quoted the offending value would put a
        // customer's address in a response body, and from there into a log
        // somewhere downstream (NFR-4).
        assertThat(body).doesNotContain("regular@example.com");
    }

    @Test
    @DisplayName("health is public, so a probe needs no credentials")
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // --- fixtures -----------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder login(
            String email, String password) {
        return post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password));
    }

    private String tokenFor(String email) throws Exception {
        String body = mvc.perform(login(email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    /** A valid token for a throwaway tenant, when the test isn't about identity. */
    private String anyToken() throws Exception {
        Business business = business("Throwaway");
        admin(business, "probe@throwaway.test");
        return tokenFor("probe@throwaway.test");
    }

    private Business business(String name) {
        Business business = new Business();
        business.setName(name);
        business.setDefaultDealValue(new BigDecimal("25.00"));
        return businesses.save(business);
    }

    private AdminUser admin(Business business, String email) {
        AdminUser admin = new AdminUser();
        admin.setBusiness(business);
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return admins.save(admin);
    }

    private Customer customer(Business business) {
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setExternalRef("cust-" + UUID.randomUUID());
        customer.setContactEmail("regular@example.com");
        customer.setTransactionCount(3);
        customer.setAvgIntervalDays(new BigDecimal("7.00"));
        customer.setLastVisitAt(Instant.now().minus(30, ChronoUnit.DAYS));
        return customers.save(customer);
    }

    private RetentionFlag flag(Business business, Customer customer) {
        RetentionFlag flag = new RetentionFlag();
        flag.setBusiness(business);
        flag.setCustomer(customer);
        flag.setAvgIntervalDaysAtFlag(customer.getAvgIntervalDays());
        flag.setThresholdDaysApplied(new BigDecimal("10.50"));
        return flags.save(flag);
    }
}
