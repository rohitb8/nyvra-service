package com.rohit.nyvra.accounts;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.user.UserProfile;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Exercises {@code /api/v1/accounts} through the real filter chain: create, read, list, update, close and
 * delete, plus ownership scoping, validation and the read-only rules for linked accounts.
 */
@AutoConfigureMockMvc
class AccountControllerIntegrationTest extends AbstractIntegrationTest {

    /** Sends requests through the real filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Used to seed an aggregator-sourced account directly. */
    @Autowired
    private FinancialAccountRepository accounts;

    /** Used to look up the provisioned caller. */
    @Autowired
    private UserProfileRepository users;

    /**
     * Authenticates a request as the given Keycloak subject.
     *
     * @param subject token {@code sub}
     * @return the JWT post-processor
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /**
     * A fresh subject so tests never share data.
     *
     * @return a unique subject
     */
    private static String newSubject() {
        return "acct-" + UUID.randomUUID();
    }

    /**
     * Creates a manual INR savings account over HTTP and checks the 201 response.
     *
     * @param subject caller
     * @param label account label
     * @return the new account id
     * @throws Exception on request failure
     */
    private String createSavings(String subject, String label) throws Exception {
        String body = mockMvc.perform(post("/api/v1/accounts").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"SAVINGS","label":"%s","institution":"HDFC Bank","maskedNumber":"XXXX1234",
                     "currentBalance":{"amount":"125000.50","currency":"INR"}}""".formatted(label)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.source", equalTo("MANUAL")))
            .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
            .andExpect(jsonPath("$.currentBalance.amount", equalTo("125000.50")))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * A created account can be fetched back with its fields intact.
     *
     * @throws Exception on request failure
     */
    @Test
    void createsAndFetchesAManualAccount() throws Exception {
        String subject = newSubject();
        String id = createSavings(subject, "Salary");

        mockMvc.perform(get("/api/v1/accounts/" + id).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label", equalTo("Salary")))
            .andExpect(jsonPath("$.maskedNumber", equalTo("XXXX1234")))
            .andExpect(jsonPath("$.currency", equalTo("INR")));
    }

    /**
     * Another user's account is indistinguishable from a missing one for get, patch and delete (404).
     *
     * @throws Exception on request failure
     */
    @Test
    void anotherUsersAccountIsA404() throws Exception {
        String id = createSavings(newSubject(), "Private");

        mockMvc.perform(get("/api/v1/accounts/" + id).with(as(newSubject())))
            .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/accounts/" + id).with(as(newSubject()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/accounts/" + id).with(as(newSubject())))
            .andExpect(status().isNotFound());
    }

    /**
     * The list is scoped to the caller, sorted, paged and hides closed accounts unless {@code status=CLOSED}.
     *
     * @throws Exception on request failure
     */
    @Test
    void listIsPagedScopedToTheCallerAndHidesClosedByDefault() throws Exception {
        String subject = newSubject();
        createSavings(subject, "B account");
        createSavings(subject, "A account");
        String closed = createSavings(subject, "Old");
        createSavings(newSubject(), "Someone else's");
        mockMvc.perform(post("/api/v1/accounts/" + closed + "/close").with(as(subject))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/accounts").param("sort", "label,asc").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].label", equalTo("A account")))
            .andExpect(jsonPath("$.totalElements", equalTo(2)))
            .andExpect(jsonPath("$.page", equalTo(0)));

        mockMvc.perform(get("/api/v1/accounts").param("status", "CLOSED").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].label", equalTo("Old")));
    }

    /**
     * Bad size, sort field, enum value or UUID path is a 400.
     *
     * @throws Exception on request failure
     */
    @Test
    void listRejectsBadPagingAndSortInputWith400() throws Exception {
        String subject = newSubject();
        mockMvc.perform(get("/api/v1/accounts").param("size", "0").with(as(subject)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/accounts").param("sort", "password,asc").with(as(subject)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/accounts").param("type", "NOPE").with(as(subject)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/accounts/not-a-uuid").with(as(subject)))
            .andExpect(status().isBadRequest());
    }

    /**
     * A credit-card account is created with its card details and credit limit.
     *
     * @throws Exception on request failure
     */
    @Test
    void createsACreditCardAccountWithCardDetails() throws Exception {
        String subject = newSubject();
        mockMvc.perform(post("/api/v1/accounts").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"CREDIT_CARD","label":"Travel card","currentBalance":{"amount":"4200.00","currency":"INR"},
                     "card":{"last4":"4821","network":"VISA","creditLimit":{"amount":"200000.00","currency":"INR"}}}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.card.last4", equalTo("4821")))
            .andExpect(jsonPath("$.card.creditLimit.amount", equalTo("200000.00")));
    }

    /**
     * Create requests with a full account number, bad amount, card mismatch or malformed JSON are 400.
     *
     * @throws Exception on request failure
     */
    @Test
    void rejectsInvalidCreateRequests() throws Exception {
        String subject = newSubject();
        // full account number (not a masked identifier)
        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"SAVINGS","label":"x","maskedNumber":"123456789012",
                     "currentBalance":{"amount":"1.00","currency":"INR"}}"""))
            .andExpect(status().isBadRequest());
        // amount as a JSON number rather than a string, too many decimals
        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SAVINGS\",\"label\":\"x\",\"currentBalance\":{\"amount\":\"1.234\",\"currency\":\"INR\"}}"))
            .andExpect(status().isBadRequest());
        // card account without card details, and card details on a non-card account
        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"CREDIT_CARD\",\"label\":\"x\",\"currentBalance\":{\"amount\":\"1.00\",\"currency\":\"INR\"}}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"SAVINGS","label":"x","currentBalance":{"amount":"1.00","currency":"INR"},
                     "card":{"last4":"4821","network":"VISA"}}"""))
            .andExpect(status().isBadRequest());
        // malformed JSON
        mockMvc.perform(post("/api/v1/accounts").with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
            .andExpect(status().isBadRequest());
    }

    /**
     * A non-INR balance is rejected with 422 UNSUPPORTED_CURRENCY.
     *
     * @throws Exception on request failure
     */
    @Test
    void rejectsNonInrCurrencyWith422() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(as(newSubject())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SAVINGS\",\"label\":\"x\",\"currentBalance\":{\"amount\":\"1.00\",\"currency\":\"USD\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("UNSUPPORTED_CURRENCY")));
    }

    /**
     * A manual account's label and balance can be patched while other fields stay unchanged.
     *
     * @throws Exception on request failure
     */
    @Test
    void updatesALabelAndABalanceOnAManualAccount() throws Exception {
        String subject = newSubject();
        String id = createSavings(subject, "Old label");

        mockMvc.perform(patch("/api/v1/accounts/" + id).with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"New label\",\"currentBalance\":{\"amount\":\"99.00\",\"currency\":\"INR\"}}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label", equalTo("New label")))
            .andExpect(jsonPath("$.currentBalance.amount", equalTo("99.00")))
            .andExpect(jsonPath("$.institution", equalTo("HDFC Bank")));
    }

    /**
     * A linked account's balance is read-only and it cannot be deleted (409), but its label can change.
     *
     * @throws Exception on request failure
     */
    @Test
    void aLinkedAccountKeepsItsBalanceReadOnlyAndCannotBeDeleted() throws Exception {
        String subject = newSubject();
        String id = linkedAccount(subject);

        mockMvc.perform(patch("/api/v1/accounts/" + id).with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentBalance\":{\"amount\":\"1.00\",\"currency\":\"INR\"}}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("SOURCE_READ_ONLY")));
        // the label is still editable
        mockMvc.perform(patch("/api/v1/accounts/" + id).with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Renamed\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/accounts/" + id).with(as(subject)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("SOURCE_READ_ONLY")));
    }

    /**
     * Closing twice is fine, and a closed account rejects edits with 409 ACCOUNT_CLOSED.
     *
     * @throws Exception on request failure
     */
    @Test
    void closingIsIdempotentAndClosedAccountsCannotBeEdited() throws Exception {
        String subject = newSubject();
        String id = createSavings(subject, "To close");

        mockMvc.perform(post("/api/v1/accounts/" + id + "/close").with(as(subject)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status", equalTo("CLOSED")));
        mockMvc.perform(post("/api/v1/accounts/" + id + "/close").with(as(subject)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status", equalTo("CLOSED")));
        mockMvc.perform(patch("/api/v1/accounts/" + id).with(as(subject)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"x\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("ACCOUNT_CLOSED")));
    }

    /**
     * Deleting a manual account returns 204 and hides it from get and list.
     *
     * @throws Exception on request failure
     */
    @Test
    void deletingAManualAccountSoftDeletesIt() throws Exception {
        String subject = newSubject();
        String id = createSavings(subject, "Temp");

        mockMvc.perform(delete("/api/v1/accounts/" + id).with(as(subject))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/accounts/" + id).with(as(subject))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/accounts").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(0)));
    }

    /**
     * An unauthenticated request is rejected with 401.
     *
     * @throws Exception on request failure
     */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
    }

    /** An AA-sourced account, which only ingestion creates. */
    private String linkedAccount(String subject) throws Exception {
        // provisions the user through the same JIT path a real request uses
        mockMvc.perform(get("/api/v1/users/me").with(as(subject))).andExpect(status().isOk());
        UserProfile user = users.findByKeycloakSubject(subject).orElseThrow();
        return accounts.save(new FinancialAccount(
            user.getId(), AccountType.SAVINGS, "SBI", "XXXX9999", "Linked", Money.inr("10.00"),
            Instant.now(), RecordSource.AA)).getId().toString();
    }
}
