package com.rohit.nyvra.portfolio;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests of the portfolio extras against a real PostgreSQL: XIRR returns, valuation history, FX
 * rates and conversion, and the admin-only price feed sync.
 */
@AutoConfigureMockMvc
class PortfolioExtrasIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controllers through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Authenticates a request as the given Keycloak subject.
     *
     * @param subject the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static JwtRequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /**
     * Authenticates a request as the given subject holding the ADMIN realm role.
     *
     * @param subject the token {@code sub}
     * @return a request post-processor attaching that JWT and role
     */
    private static JwtRequestPostProcessor admin(String subject) {
        return as(subject).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** @return a unique subject, so each test works with its own user */
    private static String newSubject() {
        return "px-" + UUID.randomUUID();
    }

    /**
     * Creates an INR equity instrument and returns its id.
     *
     * @param subject the caller
     * @return the instrument id
     * @throws Exception on request failure
     */
    private String instrument(String subject) throws Exception {
        String body = mockMvc.perform(post("/api/v1/portfolio/instruments").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"PX" + System.nanoTime() + "\",\"name\":\"Extras\",\"assetClass\":\"EQUITY\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Records a price at a moment as an administrator.
     *
     * @param instrumentId the instrument
     * @param price        the price
     * @param asOf         when it applies
     * @throws Exception on request failure
     */
    private void quote(String instrumentId, String price, Instant asOf) throws Exception {
        mockMvc.perform(post("/api/v1/portfolio/instruments/" + instrumentId + "/quotes").with(admin("px-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"" + price + "\",\"asOf\":\"" + asOf + "\"}"))
            .andExpect(status().isCreated());
    }

    /**
     * Opens a holding at a moment.
     *
     * @param subject      the caller
     * @param instrumentId the instrument
     * @param quantity     units held
     * @param avgCost      average cost
     * @param openedAt     when it was opened
     * @throws Exception on request failure
     */
    private void holding(String subject, String instrumentId, String quantity, String avgCost, Instant openedAt)
            throws Exception {
        mockMvc.perform(post("/api/v1/portfolio/holdings").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":"%s","quantity":"%s","avgCost":"%s","openedAt":"%s"}"""
                    .formatted(instrumentId, quantity, avgCost, openedAt)))
            .andExpect(status().isCreated());
    }

    /** A holding bought a year ago for 1000 and now worth 1100 returns about 10%, and unpriced ones are excluded. */
    @Test
    void computesXirrAndCountsExcludedHoldings() throws Exception {
        String subject = newSubject();
        String priced = instrument(subject);
        String unpriced = instrument(subject);
        holding(subject, priced, "10", "100", Instant.now().minus(365, ChronoUnit.DAYS));
        holding(subject, unpriced, "1", "50", Instant.now().minus(30, ChronoUnit.DAYS));
        quote(priced, "110", Instant.now().minusSeconds(5));

        mockMvc.perform(get("/api/v1/portfolio/returns").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.includedCount", equalTo(1)))
            .andExpect(jsonPath("$.excludedCount", equalTo(1)))
            .andExpect(jsonPath("$.xirrPct", equalTo(10.0)))
            .andExpect(jsonPath("$.holdings", hasSize(1)));
    }

    /** With no holdings the returns have no rate and nothing counted. */
    @Test
    void returnsHaveNoRateWithoutHoldings() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/returns").with(as(newSubject())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.includedCount", equalTo(0)))
            .andExpect(jsonPath("$.xirrPct").doesNotExist());
    }

    /** History values the price in force each day and ignores days before the holding opened. */
    @Test
    void valuesHistoryByTheLatestPriceInForce() throws Exception {
        String subject = newSubject();
        String id = instrument(subject);
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(ist);
        holding(subject, id, "2", "100", today.minusDays(2).atStartOfDay(ist).toInstant());
        quote(id, "100", today.minusDays(2).atTime(12, 0).atZone(ist).toInstant());
        quote(id, "150", today.minusDays(1).atTime(12, 0).atZone(ist).toInstant());

        mockMvc.perform(get("/api/v1/portfolio/valuation-history").with(as(subject))
                .param("from", today.minusDays(3).toString()).param("to", today.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.interval", equalTo("DAILY")))
            .andExpect(jsonPath("$.points", hasSize(4)))
            .andExpect(jsonPath("$.points[0].value.amount", equalTo("0.00")))
            .andExpect(jsonPath("$.points[0].pricedHoldingCount", equalTo(0)))
            .andExpect(jsonPath("$.points[1].value.amount", equalTo("200.00")))
            .andExpect(jsonPath("$.points[2].value.amount", equalTo("300.00")))
            .andExpect(jsonPath("$.points[3].value.amount", equalTo("300.00")))
            .andExpect(jsonPath("$.points[3].invested.amount", equalTo("200.00")));
    }

    /** An inverted or oversized range is a 400. */
    @Test
    void rejectsBadHistoryRanges() throws Exception {
        String subject = newSubject();
        mockMvc.perform(get("/api/v1/portfolio/valuation-history").with(as(subject))
                .param("from", "2026-02-01").param("to", "2026-01-01"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/portfolio/valuation-history").with(as(subject))
                .param("from", "2020-01-01").param("to", "2026-01-01"))
            .andExpect(status().isBadRequest());
    }

    /** Only admins set FX rates; a second write for the day replaces the first; conversion uses the latest rate. */
    @Test
    void storesAndConvertsFxRates() throws Exception {
        String subject = newSubject();
        String body = "{\"currency\":\"EUR\",\"rateDate\":\"" + LocalDate.now(ZoneId.of("Asia/Kolkata"))
            + "\",\"inrPerUnit\":\"90.5\"}";
        mockMvc.perform(post("/api/v1/portfolio/fx-rates").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/portfolio/fx-rates").with(admin(subject))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.inrPerUnit", equalTo("90.500000")));
        mockMvc.perform(post("/api/v1/portfolio/fx-rates").with(admin(subject))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("90.5", "91")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.inrPerUnit", equalTo("91.000000")));

        mockMvc.perform(get("/api/v1/portfolio/fx-rates").with(as(subject)).param("currency", "EUR"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].inrPerUnit", equalTo("91.000000")));
        mockMvc.perform(get("/api/v1/portfolio/fx-rates/convert").with(as(subject))
                .param("amount", "10").param("from", "EUR").param("to", "INR"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.to.amount", equalTo("910.00")))
            .andExpect(jsonPath("$.to.currency", equalTo("INR")))
            .andExpect(jsonPath("$.rateDate", notNullValue()));
        mockMvc.perform(get("/api/v1/portfolio/fx-rates/convert").with(as(subject))
                .param("amount", "182").param("from", "INR").param("to", "EUR"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.to.amount", equalTo("2.00")));
    }

    /** INR rates, future dates and unknown currencies are refused with clear codes. */
    @Test
    void validatesFxInput() throws Exception {
        String subject = newSubject();
        mockMvc.perform(post("/api/v1/portfolio/fx-rates").with(admin(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"INR\",\"inrPerUnit\":\"1\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/portfolio/fx-rates").with(admin(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"GBP\",\"rateDate\":\"2999-01-01\",\"inrPerUnit\":\"100\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("RATE_IN_FUTURE")));
        mockMvc.perform(get("/api/v1/portfolio/fx-rates/convert").with(as(subject))
                .param("amount", "10").param("from", "CHF").param("to", "INR"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("FX_RATE_UNAVAILABLE")));
    }

    /** The feed sync is admin-only and, with the stub feed, stores nothing. */
    @Test
    void syncsThePriceFeedForAdminsOnly() throws Exception {
        String subject = newSubject();
        mockMvc.perform(post("/api/v1/portfolio/price-feed/sync").with(as(subject)))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/portfolio/price-feed/sync").with(admin(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pricesStored", equalTo(0)))
            .andExpect(jsonPath("$.fxRatesStored", equalTo(0)));
    }
}
