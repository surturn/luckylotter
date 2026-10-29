package com.lucklotter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lucklotter.AbstractWebTest;
import com.lucklotter.domain.DealType;
import com.lucklotter.repo.AdminUserRepository;
import com.lucklotter.repo.BusinessRepository;
import com.lucklotter.service.ProvisioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The password lifecycle end to end through the real filter chain: a temporary
 * password from the operator, the forced change, and what a change or reset does
 * to sessions that already exist.
 */
class PasswordLifecycleTest extends AbstractWebTest {

    private static final String NEW_PASSWORD = "a-much-better-passphrase";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ProvisioningService provisioning;

    @Autowired
    private AdminUserRepository admins;

    @Autowired
    private BusinessRepository businesses;

    @AfterEach
    void cleanUp() {
        admins.deleteAllInBatch();
        businesses.deleteAllInBatch();
    }

    @Test
    @DisplayName("a temporary password signs in but is flagged as needing a change")
    void temporaryPasswordIsFlagged() throws Exception {
        var account = provision("owner@kaldis.test");

        mvc.perform(login("owner@kaldis.test", account.temporaryPassword()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    @DisplayName("until the password is changed, nothing else in the API opens (403, not 401)")
    void temporaryPasswordOpensNothingElse() throws Exception {
        var account = provision("owner@kaldis.test");
        String token = tokenFor("owner@kaldis.test", account.temporaryPassword());

        // 403, not 401: a 401 makes the dashboard sign the admin out, which
        // would turn "you must change your password" into a login loop.
        mvc.perform(get("/v1/flags").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/businesses/me/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("changing the password clears the flag and hands back a working session")
    void changeClearsTheGate() throws Exception {
        var account = provision("owner@kaldis.test");
        String temporaryToken = tokenFor("owner@kaldis.test", account.temporaryPassword());

        String body = mvc.perform(change(temporaryToken, account.temporaryPassword(), NEW_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andReturn().getResponse().getContentAsString();
        String freshToken = json.readTree(body).get("token").asText();

        mvc.perform(get("/v1/flags").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk());
        mvc.perform(login("owner@kaldis.test", NEW_PASSWORD)).andExpect(status().isOk());
        mvc.perform(login("owner@kaldis.test", account.temporaryPassword()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a change ends every session that used the old password")
    void changeEndsOldSessions() throws Exception {
        var account = provision("owner@kaldis.test");
        String temporaryToken = tokenFor("owner@kaldis.test", account.temporaryPassword());
        String changed = mvc.perform(change(temporaryToken, account.temporaryPassword(), NEW_PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sessionOnNewPassword = json.readTree(changed).get("token").asText();

        // Issue-time precision is one second, so make the next change land in a
        // later second than the session being tested.
        Thread.sleep(1100);
        mvc.perform(change(sessionOnNewPassword, NEW_PASSWORD, "yet-another-passphrase-1"))
                .andExpect(status().isOk());

        // The token from before the second change is dead, not merely expired.
        mvc.perform(get("/v1/flags").header("Authorization", "Bearer " + sessionOnNewPassword))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a wrong current password is a 400 that keeps the session, not a 401")
    void wrongCurrentPasswordKeepsTheSession() throws Exception {
        var account = provision("owner@kaldis.test");
        String token = tokenFor("owner@kaldis.test", account.temporaryPassword());

        mvc.perform(change(token, "not-the-password", NEW_PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));

        // Still gated, still able to try again.
        mvc.perform(change(token, account.temporaryPassword(), NEW_PASSWORD))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a short new password and an unchanged one are both refused")
    void weakNewPasswordIsRefused() throws Exception {
        var account = provision("owner@kaldis.test");
        String token = tokenFor("owner@kaldis.test", account.temporaryPassword());

        mvc.perform(change(token, account.temporaryPassword(), "short"))
                .andExpect(status().isBadRequest());
        mvc.perform(change(token, account.temporaryPassword(), account.temporaryPassword()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("change-password needs a token at all")
    void changeRequiresAuthentication() throws Exception {
        mvc.perform(post("/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"a-long-enough-one\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an operator reset ends the live session and puts the account back behind the gate")
    void resetEndsSessionsAndRegates() throws Exception {
        var account = provision("owner@kaldis.test");
        String changed = mvc.perform(change(
                        tokenFor("owner@kaldis.test", account.temporaryPassword()),
                        account.temporaryPassword(), NEW_PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String liveSession = json.readTree(changed).get("token").asText();
        mvc.perform(get("/v1/flags").header("Authorization", "Bearer " + liveSession))
                .andExpect(status().isOk());

        Thread.sleep(1100);
        var reset = provisioning.resetPassword("owner@kaldis.test");

        mvc.perform(get("/v1/flags").header("Authorization", "Bearer " + liveSession))
                .andExpect(status().isUnauthorized());
        mvc.perform(login("owner@kaldis.test", NEW_PASSWORD)).andExpect(status().isUnauthorized());
        mvc.perform(login("owner@kaldis.test", reset.temporaryPassword()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    @DisplayName("a deactivated admin is locked out on the very next request")
    void deactivationIsImmediate() throws Exception {
        var account = provision("owner@kaldis.test");
        String changed = mvc.perform(change(
                        tokenFor("owner@kaldis.test", account.temporaryPassword()),
                        account.temporaryPassword(), NEW_PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String liveSession = json.readTree(changed).get("token").asText();

        provisioning.deactivateAdmin("owner@kaldis.test");

        mvc.perform(get("/v1/flags").header("Authorization", "Bearer " + liveSession))
                .andExpect(status().isUnauthorized());
        mvc.perform(login("owner@kaldis.test", NEW_PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the temporary password is never stored in the clear")
    void temporaryPasswordIsHashed() {
        var account = provision("owner@kaldis.test");

        var stored = admins.findByEmailIgnoreCase("owner@kaldis.test").orElseThrow();
        assertThat(stored.getPasswordHash()).doesNotContain(account.temporaryPassword());
        assertThat(stored.getPasswordHash()).startsWith("$2");
    }

    // --- fixtures -----------------------------------------------------------

    private ProvisioningService.Provisioned provision(String adminEmail) {
        return provisioning.createBusiness(new ProvisioningService.NewBusiness(
                "Kaldi Coffee", adminEmail, "KES", "Africa/Nairobi",
                DealType.PERCENT_OFF, new BigDecimal("15"), null));
    }

    private MockHttpServletRequestBuilder login(String email, String password) {
        return post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    private MockHttpServletRequestBuilder change(String token, String current, String next) {
        return post("/v1/auth/change-password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next));
    }

    private String tokenFor(String email, String password) throws Exception {
        String body = mvc.perform(login(email, password))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
