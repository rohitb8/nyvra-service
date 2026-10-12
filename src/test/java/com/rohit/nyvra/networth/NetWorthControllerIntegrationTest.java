package com.rohit.nyvra.networth;

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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the net-worth endpoints against a real PostgreSQL/TimescaleDB: manual item CRUD and its
 * validation, the live figure across accounts and manual entries, snapshot upsert and the bucketed history,
 * and per-user isolation. Each test uses a fresh subject.
 */
@AutoConfigureMockMvc
class NetWorthControllerIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controllers through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Authenticates a request as the given Keycloak subject, with a derived email claim.
     *
     * @param subject the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /** @return a unique subject, so each test works with its own user */
    private static String newSubject() {
        return "nw-" + UUID.randomUUID();
    }

    /** @return today in the display time zone, which the module uses for snapshot days and staleness */
    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    /**
     * Creates a manual item through the API and returns its id.
     *
     * @param subject the caller
     * @param json    the request body
     * @return the new item's id
     * @throws Exception on request failure
     */
    private String createItem(String subject, String json) throws Exception {
        String body = mockMvc.perform(post("/api/v1/net-worth/manual-items").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Creates a savings account through the API.
     *
     * @param subject the caller
     * @param type    the account type
     * @param balance the balance as a decimal string
     * @throws Exception on request failure
     */
    private void createAccount(String subject, String type, String balance) throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"%s","label":"Acct","currentBalance":{"amount":"%s","currency":"INR"}}"""
                    .formatted(type, balance)))
            .andExpect(status().isCreated());
    }

    /** A new user sees zero everywhere and no breakdown. */
    @Test
    void emptyUserHasZeroNetWorth() throws Exception {
        mockMvc.perform(get("/api/v1/net-worth").with(as(newSubject())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.netWorth.amount", equalTo("0.00")))
            .andExpect(jsonPath("$.breakdown", hasSize(0)))
            .andExpect(jsonPath("$.contributingSources", hasSize(3)))
            .andExpect(jsonPath("$.staleItemCount", equalTo(0)));
    }

    /** Accounts and manual entries combine into totals, a breakdown with shares, and source contributions. */
    @Test
    void currentNetWorthCombinesAccountsAndManualItems() throws Exception {
        String s = newSubject();
        createAccount(s, "SAVINGS", "300000.00");
        createAccount(s, "LOAN", "100000.00");
        createItem(s, """
            {"kind":"ASSET","itemClass":"REAL_ESTATE","label":"Flat","value":{"amount":"700000.00","currency":"INR"}}""");

        mockMvc.perform(get("/api/v1/net-worth").with(as(s)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalAssets.amount", equalTo("1000000.00")))
            .andExpect(jsonPath("$.totalLiabilities.amount", equalTo("100000.00")))
            .andExpect(jsonPath("$.netWorth.amount", equalTo("900000.00")))
            .andExpect(jsonPath("$.breakdown", hasSize(3)))
            .andExpect(jsonPath("$.breakdown[0].bucket", equalTo("CASH")))
            .andExpect(jsonPath("$.breakdown[0].sharePercent", equalTo(30.0)))
            .andExpect(jsonPath("$.breakdown[1].bucket", equalTo("REAL_ESTATE")))
            .andExpect(jsonPath("$.breakdown[2].liability", equalTo(true)))
            .andExpect(jsonPath("$.breakdown[2].sharePercent", equalTo(100.0)))
            .andExpect(jsonPath("$.contributingSources[0].source", equalTo("ACCOUNTS")))
            .andExpect(jsonPath("$.contributingSources[2].assets.amount", equalTo("700000.00")));
    }

    /** Manual item lifecycle: create, read, list with filter, update, delete. */
    @Test
    void manualItemLifecycle() throws Exception {
        String s = newSubject();
        String id = createItem(s, """
            {"kind":"LIABILITY","itemClass":"PERSONAL_LOAN","label":"From Rahul",
             "value":{"amount":"20000.00","currency":"INR"},"revaluationCadenceDays":30}""");

        mockMvc.perform(get("/api/v1/net-worth/manual-items/" + id).with(as(s)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind", equalTo("LIABILITY")))
            .andExpect(jsonPath("$.valueAsOf", equalTo(today().toString())))
            .andExpect(jsonPath("$.revaluationCadenceDays", equalTo(30)))
            .andExpect(jsonPath("$.stale", equalTo(false)));

        mockMvc.perform(get("/api/v1/net-worth/manual-items?kind=ASSET").with(as(s)))
            .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/v1/net-worth/manual-items?kind=LIABILITY").with(as(s)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.totalElements", equalTo(1)));

        mockMvc.perform(patch("/api/v1/net-worth/manual-items/" + id).with(as(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"label":"Repaid half","value":{"amount":"10000.00","currency":"INR"}}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label", equalTo("Repaid half")))
            .andExpect(jsonPath("$.value.amount", equalTo("10000.00")));
        mockMvc.perform(get("/api/v1/net-worth").with(as(s)))
            .andExpect(jsonPath("$.totalLiabilities.amount", equalTo("10000.00")));

        mockMvc.perform(delete("/api/v1/net-worth/manual-items/" + id).with(as(s)))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/net-worth/manual-items/" + id).with(as(s)))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/net-worth").with(as(s)))
            .andExpect(jsonPath("$.totalLiabilities.amount", equalTo("0.00")));
    }

    /** An entry whose cadence has elapsed is reported stale, per entry and in the total count. */
    @Test
    void staleEntriesAreFlagged() throws Exception {
        String s = newSubject();
        String id = createItem(s, """
            {"kind":"ASSET","itemClass":"VEHICLE","value":{"amount":"400000.00","currency":"INR"},
             "valueAsOf":"%s","revaluationCadenceDays":90}""".formatted(today().minusDays(120)));

        mockMvc.perform(get("/api/v1/net-worth/manual-items/" + id).with(as(s)))
            .andExpect(jsonPath("$.stale", equalTo(true)));
        mockMvc.perform(get("/api/v1/net-worth").with(as(s)))
            .andExpect(jsonPath("$.staleItemCount", equalTo(1)));

        mockMvc.perform(patch("/api/v1/net-worth/manual-items/" + id).with(as(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"value":{"amount":"380000.00","currency":"INR"}}"""))
            .andExpect(jsonPath("$.stale", equalTo(false)))
            .andExpect(jsonPath("$.valueAsOf", equalTo(today().toString())));
    }

    /** Domain rules return 422 with stable codes; structural problems return 400. */
    @Test
    void validationRules() throws Exception {
        String s = newSubject();
        post422(s, """
            {"kind":"ASSET","itemClass":"PERSONAL_LOAN","value":{"amount":"1.00","currency":"INR"}}""",
            "CLASS_KIND_MISMATCH");
        post422(s, """
            {"kind":"LIABILITY","itemClass":"REAL_ESTATE","value":{"amount":"1.00","currency":"INR"}}""",
            "CLASS_KIND_MISMATCH");
        post422(s, """
            {"kind":"ASSET","itemClass":"OTHER","value":{"amount":"1.00","currency":"USD"}}""",
            "UNSUPPORTED_CURRENCY");
        post422(s, """
            {"kind":"ASSET","itemClass":"OTHER","value":{"amount":"-1.00","currency":"INR"}}""",
            "NEGATIVE_VALUE");
        post422(s, """
            {"kind":"ASSET","itemClass":"OTHER","value":{"amount":"1.00","currency":"INR"},"valueAsOf":"%s"}"""
            .formatted(today().plusDays(2)), "VALUE_DATE_IN_FUTURE");

        mockMvc.perform(post("/api/v1/net-worth/manual-items").with(as(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"itemClass":"OTHER","value":{"amount":"1.00","currency":"INR"}}"""))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/net-worth/manual-items").with(as(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"kind":"ASSET","itemClass":"OTHER","value":{"amount":"1.00","currency":"INR"},
                     "revaluationCadenceDays":0}"""))
            .andExpect(status().isBadRequest());
    }

    /**
     * Posts an entry that must be rejected as a domain-rule violation.
     *
     * @param subject the caller
     * @param json    the request body
     * @param code    the expected {@code ApiError.code}
     * @throws Exception on request failure
     */
    private void post422(String subject, String json, String code) throws Exception {
        mockMvc.perform(post("/api/v1/net-worth/manual-items").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo(code)));
    }

    /** Another user's entry looks like a missing one, and never leaks into the other user's totals. */
    @Test
    void usersAreIsolated() throws Exception {
        String owner = newSubject();
        String other = newSubject();
        String id = createItem(owner, """
            {"kind":"ASSET","itemClass":"JEWELLERY","value":{"amount":"90000.00","currency":"INR"}}""");

        mockMvc.perform(get("/api/v1/net-worth/manual-items/" + id).with(as(other))).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/net-worth/manual-items/" + id).with(as(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/net-worth/manual-items/" + id).with(as(other)))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/net-worth").with(as(other)))
            .andExpect(jsonPath("$.netWorth.amount", equalTo("0.00")));
    }

    /** Recording a snapshot twice on the same day keeps one point, holding the latest figures. */
    @Test
    void snapshotsAreUpsertedPerDay() throws Exception {
        String s = newSubject();
        createAccount(s, "SAVINGS", "1000.00");
        mockMvc.perform(post("/api/v1/net-worth/snapshots").with(as(s)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.netWorth.amount", equalTo("1000.00")));
        createAccount(s, "SAVINGS", "500.00");
        mockMvc.perform(post("/api/v1/net-worth/snapshots").with(as(s)))
            .andExpect(jsonPath("$.netWorth.amount", equalTo("1500.00")));

        mockMvc.perform(get("/api/v1/net-worth/history?granularity=DAY").with(as(s)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.granularity", equalTo("DAY")))
            .andExpect(jsonPath("$.points", hasSize(1)))
            .andExpect(jsonPath("$.points[0].netWorth.amount", equalTo("1500.00")))
            .andExpect(jsonPath("$.change").doesNotExist());
    }

    /** Changing a manual entry refreshes today's snapshot, so the trend follows without an explicit call. */
    @Test
    void manualChangesRefreshTheSnapshot() throws Exception {
        String s = newSubject();
        String id = createItem(s, """
            {"kind":"ASSET","itemClass":"VEHICLE","value":{"amount":"250000.00","currency":"INR"}}""");
        mockMvc.perform(get("/api/v1/net-worth/history").with(as(s)))
            .andExpect(jsonPath("$.granularity", equalTo("WEEK")))
            .andExpect(jsonPath("$.points", hasSize(1)))
            .andExpect(jsonPath("$.points[0].netWorth.amount", equalTo("250000.00")));

        mockMvc.perform(delete("/api/v1/net-worth/manual-items/" + id).with(as(s))).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/net-worth/history").with(as(s)))
            .andExpect(jsonPath("$.points", hasSize(1)))
            .andExpect(jsonPath("$.points[0].netWorth.amount", equalTo("0.00")));
    }

    /** History validates its range and honours explicit bounds. */
    @Test
    void historyRangeRules() throws Exception {
        String s = newSubject();
        mockMvc.perform(get("/api/v1/net-worth/history?from=2026-02-01&to=2026-01-01").with(as(s)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/net-worth/history?from=2000-01-01&to=2026-01-01").with(as(s)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/net-worth/history?granularity=YEAR").with(as(s)))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/net-worth/history?from=2020-01-01&to=2020-01-31").with(as(s)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.points", hasSize(0)))
            .andExpect(jsonPath("$.from", equalTo("2020-01-01")));
    }

    /** All endpoints require authentication. */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/net-worth")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/net-worth/manual-items")).andExpect(status().isUnauthorized());
    }
}
