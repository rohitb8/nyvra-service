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

@AutoConfigureMockMvc
class AccountControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FinancialAccountRepository accounts;

    @Autowired
    private UserProfileRepository users;

    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    private static String newSubject() {
        return "acct-" + UUID.randomUUID();
    }

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

    @Test
    void rejectsNonInrCurrencyWith422() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(as(newSubject())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SAVINGS\",\"label\":\"x\",\"currentBalance\":{\"amount\":\"1.00\",\"currency\":\"USD\"}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("UNSUPPORTED_CURRENCY")));
    }

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

    @Test
    void deletingAManualAccountSoftDeletesIt() throws Exception {
        String subject = newSubject();
        String id = createSavings(subject, "Temp");

        mockMvc.perform(delete("/api/v1/accounts/" + id).with(as(subject))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/accounts/" + id).with(as(subject))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/accounts").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(0)));
    }

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
