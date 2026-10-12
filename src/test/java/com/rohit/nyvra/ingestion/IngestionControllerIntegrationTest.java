package com.rohit.nyvra.ingestion;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.jayway.jsonpath.JsonPath;
import com.rohit.nyvra.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * End-to-end tests of the ingestion API against a real PostgreSQL with a scripted aggregator: the consent
 * state machine, ownership scoping (404), idempotent fetches, encrypted raw storage, normalisation and the
 * published events. Each test uses a fresh subject so tests never see each other's data.
 */
@AutoConfigureMockMvc
@Import(IngestionControllerIntegrationTest.ScriptedAggregator.class)
class IngestionControllerIntegrationTest extends AbstractIntegrationTest {

    /** Aggregator double whose fetch result and failure can be set per test, plus a listener recording events. */
    @TestConfiguration
    static class ScriptedAggregator {

        /** Payloads the next fetch returns. */
        static volatile List<AggregatorGateway.FiPayload> payloads = List.of();

        /** When set, the next fetch fails with this message. */
        static volatile String failure;

        /** Events published during the current test. */
        static final List<Object> EVENTS = new CopyOnWriteArrayList<>();

        /**
         * Replaces the stub gateway for this test context.
         *
         * @return a gateway returning {@link #payloads} or failing with {@link #failure}
         */
        @Bean
        @Primary
        AggregatorGateway scriptedGateway() {
            return new AggregatorGateway() {
                @Override
                public String requestConsent(String aaHandle, List<String> fipList, LocalDate from, LocalDate to,
                                             String frequency) {
                    return "handle-" + UUID.randomUUID();
                }

                @Override
                public Instant confirmConsent(String consentHandle, String consentId) {
                    return Instant.now().plus(30, ChronoUnit.DAYS);
                }

                @Override
                public void revokeConsent(String consentId) {
                    // accepted
                }

                @Override
                public List<FiPayload> fetch(String consentId, LocalDate from, LocalDate to) {
                    if (failure != null) {
                        throw new AggregatorException(failure);
                    }
                    return payloads;
                }
            };
        }

        /**
         * Records the ingestion events published during a test.
         *
         * @param event the transactions event
         */
        @EventListener
        void on(TransactionsIngested event) {
            EVENTS.add(event);
        }

        /**
         * Records failure events published during a test.
         *
         * @param event the failure event
         */
        @EventListener
        void on(IngestionFailed event) {
            EVENTS.add(event);
        }
    }

    /** Drives the controller through the full filter chain. */
    @Autowired
    private MockMvc mockMvc;

    /** Used to confirm raw payloads are stored encrypted. */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Resets the scripted aggregator before each test. */
    @BeforeEach
    void reset() {
        ScriptedAggregator.payloads = List.of();
        ScriptedAggregator.failure = null;
        ScriptedAggregator.EVENTS.clear();
    }

    /**
     * Authenticates a request as the given subject.
     *
     * @param subject the token {@code sub}
     * @return a request post-processor attaching that JWT
     */
    private static RequestPostProcessor as(String subject) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("email", subject + "@nyvra.local"));
    }

    /** @return a unique subject, so each test works with its own user */
    private static String newSubject() {
        return "ing-" + UUID.randomUUID();
    }

    /**
     * Requests a consent for 2025-01-01..2025-06-30 and returns its id.
     *
     * @param subject the caller
     * @return the consent id
     * @throws Exception on request failure
     */
    private String requestConsent(String subject) throws Exception {
        String body = mockMvc.perform(post("/api/v1/ingestion/consents").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"aaHandle":"someone@onemoney","fipList":["HDFC"],
                     "dataRangeFrom":"2025-01-01","dataRangeTo":"2025-06-30","frequency":"1/DAY"}"""))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.fipList[0]").value("HDFC"))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /**
     * Requests and activates a consent.
     *
     * @param subject the caller
     * @return the active consent id
     * @throws Exception on request failure
     */
    private String activeConsent(String subject) throws Exception {
        String id = requestConsent(subject);
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/activate", id).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"consentId\":\"c-" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.expiresAt").exists());
        return id;
    }

    /** A consent walks the full state machine, and illegal moves are 409 with a domain code. */
    @Test
    void consentFollowsItsStateMachine() throws Exception {
        String subject = newSubject();
        String id = requestConsent(subject);

        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/pause", id).with(as(subject)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_CONSENT_STATE"));

        String activation = "{\"consentId\":\"c-" + UUID.randomUUID() + "\"}";
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/activate", id).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(activation))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/activate", id).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(activation))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_CONSENT_STATE"));
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/pause", id).with(as(subject)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAUSED"));
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/resume", id).with(as(subject)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/revoke", id).with(as(subject)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/resume", id).with(as(subject)))
            .andExpect(status().isConflict());
    }

    /** A request with a reversed range is a 400 for missing fields and a 422 for an inverted range. */
    @Test
    void createConsentValidatesInput() throws Exception {
        String subject = newSubject();
        mockMvc.perform(post("/api/v1/ingestion/consents").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"aaHandle\":\"\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/ingestion/consents").with(as(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"aaHandle\":\"a@b\",\"dataRangeFrom\":\"2025-06-01\",\"dataRangeTo\":\"2025-01-01\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INVALID_DATA_RANGE"));
    }

    /** Another user's consent and fetch are 404, and the lists only show the caller's own. */
    @Test
    void ownershipIsEnforced() throws Exception {
        String owner = newSubject();
        String intruder = newSubject();
        String id = activeConsent(owner);

        mockMvc.perform(get("/api/v1/ingestion/consents/{id}", id).with(as(intruder)))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/fetches", id).with(as(intruder)))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/ingestion/consents").with(as(intruder)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content", hasSize(0)));
        mockMvc.perform(get("/api/v1/ingestion/consents").with(as(owner)))
            .andExpect(jsonPath("$.content", hasSize(1)));

        String body = mockMvc.perform(post("/api/v1/ingestion/consents/{id}/fetches", id).with(as(owner)))
            .andReturn().getResponse().getContentAsString();
        String fetchId = JsonPath.read(body, "$.id");
        mockMvc.perform(get("/api/v1/ingestion/fetches/{id}", fetchId).with(as(intruder)))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/ingestion/fetches").with(as(intruder)))
            .andExpect(jsonPath("$.content", hasSize(0)));
    }

    /** A fetch stores the payload encrypted, normalises it, publishes the event and is idempotent per range. */
    @Test
    void fetchStoresEncryptedRawNormalisesAndIsIdempotent() throws Exception {
        String subject = newSubject();
        String id = activeConsent(subject);
        ScriptedAggregator.payloads = List.of(
            new AggregatorGateway.FiPayload("HDFC", "acct-" + id, PayloadType.DEPOSIT, """
                {"transactions":[{"type":"DEBIT","amount":"250.50","valueDate":"2025-05-31","narration":"UPI-Swiggy"}]}"""),
            new AggregatorGateway.FiPayload("HDFC", "mf-1", PayloadType.MUTUAL_FUND, "{}"));

        String body = mockMvc.perform(post("/api/v1/ingestion/consents/{id}/fetches", id).with(as(subject)))
            .andExpect(status().isAccepted())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.fiTypes[0]").value("DEPOSIT"))
            .andExpect(jsonPath("$.normalisation.producedEvents").value(1))
            .andExpect(jsonPath("$.normalisation.unmapped.MALFORMED_PAYLOAD").value(1))
            .andReturn().getResponse().getContentAsString();
        String fetchId = JsonPath.read(body, "$.id");

        assertThat(ScriptedAggregator.EVENTS).hasSize(1);
        TransactionsIngested event = (TransactionsIngested) ScriptedAggregator.EVENTS.get(0);
        assertThat(event.transactions()).hasSize(1);
        assertThat(event.transactions().get(0).narration()).isEqualTo("UPI-Swiggy");

        byte[] stored = jdbc.queryForObject(
            "SELECT raw_json FROM raw_financial_record WHERE fetch_session_id = ? AND payload_type = 'DEPOSIT'",
            byte[].class, UUID.fromString(fetchId));
        assertThat(new String(stored)).doesNotContain("Swiggy");

        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/fetches", id).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(equalTo(fetchId)));
        assertThat(ScriptedAggregator.EVENTS).hasSize(1);

        mockMvc.perform(post("/api/v1/ingestion/fetches/{id}/normalise", fetchId).with(as(subject)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.normalisation.producedEvents").value(1));
        assertThat(ScriptedAggregator.EVENTS).hasSize(2);
        assertThat(((TransactionsIngested) ScriptedAggregator.EVENTS.get(1)).transactions().get(0).dedupKey())
            .isEqualTo(event.transactions().get(0).dedupKey());

        mockMvc.perform(get("/api/v1/ingestion/fetches").param("consentId", id).with(as(subject)))
            .andExpect(jsonPath("$.content", hasSize(1)))
            .andExpect(jsonPath("$.content[0].normalisation.producedEvents").value(1));
    }

    /** No fetch is allowed unless the consent is active, and the range must sit inside the consent's range. */
    @Test
    void fetchRequiresActiveConsentAndValidRange() throws Exception {
        String subject = newSubject();
        String pending = requestConsent(subject);
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/fetches", pending).with(as(subject)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONSENT_NOT_ACTIVE"));

        String active = activeConsent(subject);
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/fetches", active).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"2024-01-01\",\"to\":\"2025-02-01\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("FETCH_RANGE_INVALID"));
        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/fetches", active).with(as(subject))
                .contentType(MediaType.APPLICATION_JSON).content("{\"from\":\"2025-03-01\",\"to\":\"2025-02-01\"}"))
            .andExpect(status().isUnprocessableEntity());
    }

    /** An aggregator failure is recorded on the session and announced, not turned into a 5xx. */
    @Test
    void aggregatorFailureIsRecordedOnTheSession() throws Exception {
        String subject = newSubject();
        String id = activeConsent(subject);
        ScriptedAggregator.failure = "Provider unavailable";

        mockMvc.perform(post("/api/v1/ingestion/consents/{id}/fetches", id).with(as(subject)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.error").value("Provider unavailable"));

        assertThat(new ArrayList<>(ScriptedAggregator.EVENTS)).singleElement().isInstanceOf(IngestionFailed.class);
    }
}
