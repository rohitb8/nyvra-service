package com.rohit.nyvra.networth;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the snapshot maintenance added to the net-worth module: the real portfolio valuation
 * feeding net worth, automatic snapshot refresh when accounts or holdings change, and the daily job body that
 * snapshots every user. They run against a real database through the full filter chain, each test with a fresh
 * subject.
 */
@AutoConfigureMockMvc
class NetWorthSnapshotIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controllers through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Used to clear snapshots the change listener wrote, so the job's own work is observable. */
    @Autowired
    private JdbcTemplate jdbc;

    /** The job body under test. */
    @Autowired
    private NetWorthSnapshotRunner runner;

    /**
     * Authenticates a request as the given Keycloak subject.
     *
     * @param subject the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /** @return a unique subject, so each test works with its own user */
    private static String newSubject() {
        return "nws-" + UUID.randomUUID();
    }

    /**
     * Creates a savings account through the API and returns its id.
     *
     * @param subject the caller
     * @param balance the opening balance
     * @return the account id
     * @throws Exception on request failure
     */
    private String createAccount(String subject, String balance) throws Exception {
        String body = mockMvc.perform(post("/api/v1/accounts").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"SAVINGS","label":"Acct","currentBalance":{"amount":"%s","currency":"INR"}}"""
                    .formatted(balance)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Creates an instrument with a unique symbol and returns its id.
     *
     * @param subject the caller
     * @return the instrument id
     * @throws Exception on request failure
     */
    private String createInstrument(String subject) throws Exception {
        String symbol = "NW" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String body = mockMvc.perform(post("/api/v1/portfolio/instruments").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"symbol":"%s","name":"%s Ltd","assetClass":"EQUITY"}""".formatted(symbol, symbol)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Opens a holding through the API.
     *
     * @param subject      the caller
     * @param instrumentId the instrument
     * @param quantity     units held
     * @param avgCost      average cost per unit
     * @return the holding id
     * @throws Exception on request failure
     */
    private String createHolding(String subject, String instrumentId, String quantity, String avgCost)
            throws Exception {
        String body = mockMvc.perform(post("/api/v1/portfolio/holdings").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":"%s","quantity":"%s","avgCost":"%s"}""".formatted(instrumentId, quantity, avgCost)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Records a manual price for an instrument.
     *
     * @param subject      the caller
     * @param instrumentId the instrument
     * @param price        the price
     * @throws Exception on request failure
     */
    private void quote(String subject, String instrumentId, String price) throws Exception {
        mockMvc.perform(post("/api/v1/portfolio/instruments/" + instrumentId + "/quotes").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":\"" + price + "\"}"))
            .andExpect(status().isCreated());
    }

    /**
     * Asserts today's trend point and that it is the only one.
     *
     * @param subject  the caller
     * @param netWorth the expected net worth, scale 2
     * @throws Exception on request failure
     */
    private void assertSingleSnapshot(String subject, String netWorth) throws Exception {
        mockMvc.perform(get("/api/v1/net-worth/history?granularity=DAY").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.points", hasSize(1)))
            .andExpect(jsonPath("$.points[0].netWorth.amount", equalTo(netWorth)));
    }

    /** Holdings are valued at the latest quote and count towards the live net worth under investments. */
    @Test
    void portfolioHoldingsFeedNetWorth() throws Exception {
        String s = newSubject();
        String instrument = createInstrument(s);
        createHolding(s, instrument, "10", "100");
        quote(s, instrument, "150.5");

        mockMvc.perform(get("/api/v1/net-worth").with(as(s)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.netWorth.amount", equalTo("1505.00")))
            .andExpect(jsonPath("$.contributingSources[?(@.source=='PORTFOLIO')].assets.amount",
                contains("1505.00")));
    }

    /** A holding with no quote yet is carried at cost instead of disappearing. */
    @Test
    void unpricedHoldingFallsBackToCost() throws Exception {
        String s = newSubject();
        createHolding(s, createInstrument(s), "4", "250");

        mockMvc.perform(get("/api/v1/net-worth").with(as(s)))
            .andExpect(jsonPath("$.netWorth.amount", equalTo("1000.00")));
    }

    /** Creating, changing and deleting a holding or account refreshes today's snapshot without an explicit call. */
    @Test
    void accountAndHoldingChangesRefreshTheSnapshot() throws Exception {
        String s = newSubject();
        String account = createAccount(s, "1000.00");
        assertSingleSnapshot(s, "1000.00");

        String instrument = createInstrument(s);
        quote(s, instrument, "10");
        String holding = createHolding(s, instrument, "5", "8");
        assertSingleSnapshot(s, "1050.00");

        mockMvc.perform(delete("/api/v1/portfolio/holdings/" + holding).with(as(s))).andExpect(status().isNoContent());
        assertSingleSnapshot(s, "1000.00");

        mockMvc.perform(delete("/api/v1/accounts/" + account).with(as(s))).andExpect(status().isNoContent());
        assertSingleSnapshot(s, "0.00");
    }

    /** The job body snapshots every user, including ones who never changed anything, and is idempotent per day. */
    @Test
    void dailyRunSnapshotsEveryUser() throws Exception {
        String s = newSubject();
        // Creating an account provisions the user; then drop the snapshot that change wrote, leaving a user
        // the job has to catch up.
        createAccount(s, "0.00");
        jdbc.update("DELETE FROM net_worth_snapshot WHERE user_id = (SELECT id FROM user_profile "
            + "WHERE keycloak_subject = ?)", s);
        mockMvc.perform(get("/api/v1/net-worth/history?granularity=DAY").with(as(s)))
            .andExpect(jsonPath("$.points", hasSize(0)));

        NetWorthSnapshotRunner.Result first = runner.recordForAllUsers();
        runner.recordForAllUsers();

        org.junit.jupiter.api.Assertions.assertTrue(first.recorded() >= 1);
        org.junit.jupiter.api.Assertions.assertEquals(0, first.failed());
        assertSingleSnapshot(s, "0.00");
    }
}
