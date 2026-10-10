package com.rohit.nyvra.portfolio;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the portfolio API against a real PostgreSQL (Testcontainers) with a stub JWT decoder:
 * instrument creation and search, manual quotes, holding lifecycle (open, adjust, close, reopen, delete),
 * ownership scoping (404 for other users), validation (400), business-rule errors (409/422 with codes) and
 * the summary figures. Each test uses a fresh subject and fresh instruments so tests never see each other's data.
 */
@AutoConfigureMockMvc
class PortfolioControllerIntegrationTest extends AbstractIntegrationTest {

    /** Drives the controller through the full security and servlet filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Used to insert a holding that did not come from the user, which the API cannot create. */
    @Autowired
    private PortfolioHoldingRepository holdings;

    /** Used to insert an instrument priced in a foreign currency, and to resolve the user id. */
    @Autowired
    private InstrumentRepository instruments;

    /** Resolves the internal user id that JIT provisioning created for a subject. */
    @Autowired
    private UserProfileRepository users;

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
        return "pf-" + UUID.randomUUID();
    }

    /**
     * Creates an instrument through the API and checks the 201 response.
     *
     * @param subject the caller
     * @param symbol a symbol unique to the test
     * @param assetClass the asset class
     * @return the new instrument id
     * @throws Exception on request failure
     */
    private String createInstrument(String subject, String symbol, String assetClass) throws Exception {
        String body = mockMvc.perform(post("/api/v1/portfolio/instruments").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"symbol":"%s","name":"%s Ltd","assetClass":"%s"}""".formatted(symbol, symbol, assetClass)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.currency", equalTo("INR")))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Records a price for an instrument and checks the 201 response.
     *
     * @param subject the caller
     * @param instrumentId the instrument
     * @param price the price as a decimal string
     * @throws Exception on request failure
     */
    private void quote(String subject, String instrumentId, String price) throws Exception {
        mockMvc.perform(post("/api/v1/portfolio/instruments/" + instrumentId + "/quotes").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":\"" + price + "\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.source", equalTo("MANUAL")));
    }

    /**
     * Opens a holding through the API and checks the 201 response.
     *
     * @param subject the caller
     * @param instrumentId the instrument
     * @param quantity units held
     * @param avgCost average cost per unit
     * @return the new holding id
     * @throws Exception on request failure
     */
    private String createHolding(String subject, String instrumentId, String quantity, String avgCost)
            throws Exception {
        String body = mockMvc.perform(post("/api/v1/portfolio/holdings").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"instrumentId":"%s","quantity":"%s","avgCost":"%s"}""".formatted(instrumentId, quantity, avgCost)))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.source", equalTo("MANUAL")))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * An instrument can be created, fetched and found by a case-insensitive search; a repeated ISIN is a
     * 409, and an instrument with neither ISIN nor symbol is a 400.
     */
    @Test
    void createsSearchesAndRejectsDuplicateInstruments() throws Exception {
        String subject = newSubject();
        String symbol = "SRCH" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String id = createInstrument(subject, symbol, "EQUITY");

        mockMvc.perform(get("/api/v1/portfolio/instruments/" + id).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol", equalTo(symbol)));
        mockMvc.perform(get("/api/v1/portfolio/instruments").param("q", symbol.toLowerCase())
                .param("assetClass", "EQUITY").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].id", equalTo(id)));

        String isin = "INE" + String.format("%09d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000L)
            .substring(0, 8) + "0";
        String json = "{\"isin\":\"" + isin + "\",\"assetClass\":\"EQUITY\"}";
        mockMvc.perform(post("/api/v1/portfolio/instruments").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/portfolio/instruments").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("INSTRUMENT_ISIN_EXISTS")));
        mockMvc.perform(post("/api/v1/portfolio/instruments").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"assetClass\":\"EQUITY\"}"))
            .andExpect(status().isBadRequest());
    }

    /** A holding with a price shows invested value, current value, gain and gain percentage. */
    @Test
    void valuesAHoldingFromItsLatestQuote() throws Exception {
        String subject = newSubject();
        String instrument = createInstrument(subject, "VAL" + System.nanoTime(), "EQUITY");
        String holding = createHolding(subject, instrument, "10", "100");
        mockMvc.perform(get("/api/v1/portfolio/holdings/" + holding).with(as(subject)))
            .andExpect(jsonPath("$.investedValue.amount", equalTo("1000.00")))
            .andExpect(jsonPath("$.currentValue").doesNotExist())
            .andExpect(jsonPath("$.latestPrice").doesNotExist());

        quote(subject, instrument, "125.5");
        mockMvc.perform(get("/api/v1/portfolio/holdings/" + holding).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quantity", equalTo("10.000000")))
            .andExpect(jsonPath("$.latestPrice", equalTo("125.500000")))
            .andExpect(jsonPath("$.currentValue.amount", equalTo("1255.00")))
            .andExpect(jsonPath("$.unrealisedGain.amount", equalTo("255.00")))
            .andExpect(jsonPath("$.unrealisedGainPct", equalTo(25.5)));
    }

    /** Setting the quantity to zero closes a holding and hides it from the default list; a positive quantity reopens it. */
    @Test
    void closesAndReopensAHolding() throws Exception {
        String subject = newSubject();
        String instrument = createInstrument(subject, "CLS" + System.nanoTime(), "MUTUAL_FUND");
        String holding = createHolding(subject, instrument, "5", "20");

        mockMvc.perform(patch("/api/v1/portfolio/holdings/" + holding).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"0\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.closedAt").exists());
        mockMvc.perform(get("/api/v1/portfolio/holdings").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/v1/portfolio/holdings").param("includeClosed", "true").with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(patch("/api/v1/portfolio/holdings/" + holding).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"7.5\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.closedAt").doesNotExist())
            .andExpect(jsonPath("$.quantity", equalTo("7.500000")));
    }

    /** A second open holding of the same instrument is a 409, and an empty patch is a 400. */
    @Test
    void rejectsDuplicateOpenHoldingAndEmptyPatch() throws Exception {
        String subject = newSubject();
        String instrument = createInstrument(subject, "DUP" + System.nanoTime(), "EQUITY");
        String holding = createHolding(subject, instrument, "1", "10");

        mockMvc.perform(post("/api/v1/portfolio/holdings").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrumentId\":\"" + instrument + "\",\"quantity\":\"2\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("HOLDING_ALREADY_EXISTS")));
        mockMvc.perform(patch("/api/v1/portfolio/holdings/" + holding).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    /** Invalid input is a 400: a zero opening quantity, a malformed decimal and an unknown instrument is a 404. */
    @Test
    void validatesHoldingInput() throws Exception {
        String subject = newSubject();
        String instrument = createInstrument(subject, "VLD" + System.nanoTime(), "EQUITY");

        mockMvc.perform(post("/api/v1/portfolio/holdings").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrumentId\":\"" + instrument + "\",\"quantity\":\"0\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/portfolio/holdings").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrumentId\":\"" + instrument + "\",\"quantity\":\"1.1234567\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/portfolio/holdings").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrumentId\":\"" + UUID.randomUUID() + "\",\"quantity\":\"1\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/portfolio/holdings").param("sort", "bogus,asc").with(as(subject)))
            .andExpect(status().isBadRequest());
    }

    /** A holding in a non-INR instrument is refused with {@code UNSUPPORTED_CURRENCY}. */
    @Test
    void rejectsNonInrInstrumentHoldings() throws Exception {
        String subject = newSubject();
        Instrument usd = instruments.save(new Instrument(null, "USD" + System.nanoTime(), "US stock",
            AssetClass.FOREIGN_EQUITY, "USD", "US"));
        mockMvc.perform(post("/api/v1/portfolio/holdings").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"instrumentId\":\"" + usd.getId() + "\",\"quantity\":\"1\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("UNSUPPORTED_CURRENCY")));
    }

    /** Another user's holding is a 404 for read, update and delete; deleting one's own returns 204 then 404. */
    @Test
    void scopesHoldingsToTheirOwner() throws Exception {
        String owner = newSubject();
        String other = newSubject();
        String instrument = createInstrument(owner, "OWN" + System.nanoTime(), "EQUITY");
        String holding = createHolding(owner, instrument, "3", "50");

        mockMvc.perform(get("/api/v1/portfolio/holdings/" + holding).with(as(other)))
            .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/portfolio/holdings/" + holding).with(as(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"1\"}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/portfolio/holdings/" + holding).with(as(other)))
            .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/portfolio/holdings/" + holding).with(as(owner)))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/portfolio/holdings/" + holding).with(as(owner)))
            .andExpect(status().isNotFound());
    }

    /** A holding that did not come from the user cannot be changed or deleted (409 {@code HOLDING_READ_ONLY}). */
    @Test
    void importedHoldingsAreReadOnly() throws Exception {
        String subject = newSubject();
        String instrument = createInstrument(subject, "IMP" + System.nanoTime(), "EQUITY");
        createHolding(subject, createInstrument(subject, "IMPB" + System.nanoTime(), "EQUITY"), "1", "1");
        UUID userId = users.findByKeycloakSubject(subject).orElseThrow().getId();
        Instrument held = instruments.findById(UUID.fromString(instrument)).orElseThrow();
        PortfolioHolding imported = holdings.save(new PortfolioHolding(userId, held, new BigDecimal("2"),
            new BigDecimal("10"), RecordSource.AA, Instant.now()));

        mockMvc.perform(patch("/api/v1/portfolio/holdings/" + imported.getId()).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":\"1\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code", equalTo("HOLDING_READ_ONLY")));
        mockMvc.perform(delete("/api/v1/portfolio/holdings/" + imported.getId()).with(as(subject)))
            .andExpect(status().isConflict());
    }

    /** A quote dated in the future is a 422 and a malformed price is a 400. */
    @Test
    void validatesQuotes() throws Exception {
        String subject = newSubject();
        String instrument = createInstrument(subject, "QTE" + System.nanoTime(), "EQUITY");
        mockMvc.perform(post("/api/v1/portfolio/instruments/" + instrument + "/quotes").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"price\":\"10\",\"asOf\":\"" + Instant.now().plusSeconds(3600) + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code", equalTo("QUOTE_IN_FUTURE")));
        mockMvc.perform(post("/api/v1/portfolio/instruments/" + instrument + "/quotes").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"price\":\"-1\"}"))
            .andExpect(status().isBadRequest());
    }

    /** With no holdings the summary is zero and has no gain; with priced and unpriced holdings it totals and allocates. */
    @Test
    void summarisesTotalsAndAllocation() throws Exception {
        String subject = newSubject();
        mockMvc.perform(get("/api/v1/portfolio/summary").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.investedValue.amount", equalTo("0.00")))
            .andExpect(jsonPath("$.unrealisedGain").doesNotExist())
            .andExpect(jsonPath("$.allocation", hasSize(0)));

        String equity = createInstrument(subject, "SUME" + System.nanoTime(), "EQUITY");
        String fund = createInstrument(subject, "SUMF" + System.nanoTime(), "MUTUAL_FUND");
        createHolding(subject, equity, "10", "100");
        createHolding(subject, fund, "100", "5");
        quote(subject, equity, "150");

        mockMvc.perform(get("/api/v1/portfolio/summary").with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency", equalTo("INR")))
            .andExpect(jsonPath("$.investedValue.amount", equalTo("1500.00")))
            .andExpect(jsonPath("$.currentValue.amount", equalTo("1500.00")))
            .andExpect(jsonPath("$.unrealisedGain.amount", equalTo("500.00")))
            .andExpect(jsonPath("$.unrealisedGainPct", equalTo(50.0)))
            .andExpect(jsonPath("$.holdingCount", equalTo(2)))
            .andExpect(jsonPath("$.unpricedHoldingCount", equalTo(1)))
            .andExpect(jsonPath("$.excludedHoldingCount", equalTo(0)))
            .andExpect(jsonPath("$.allocation", hasSize(2)))
            .andExpect(jsonPath("$.allocation[0].assetClass", equalTo("EQUITY")))
            .andExpect(jsonPath("$.allocation[0].value.amount", equalTo("1500.00")))
            .andExpect(jsonPath("$.allocation[0].percent", equalTo(75.0)))
            .andExpect(jsonPath("$.allocation[1].assetClass", equalTo("MUTUAL_FUND")))
            .andExpect(jsonPath("$.allocation[1].percent", equalTo(25.0)));
    }

    /** Requests without a token are rejected. */
    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/summary")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/portfolio/holdings")).andExpect(status().isUnauthorized());
    }
}
