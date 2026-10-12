package com.rohit.nyvra.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Unit tests for {@link HttpAggregatorGateway} against a mocked HTTP server: the assumed wire shape and error mapping. */
class HttpAggregatorGatewayTest {

    /** Base URL the mocked gateway answers on. */
    private static final String BASE = "https://aa.example.test";

    /** The mocked HTTP server. */
    private MockRestServiceServer server;

    /** The unit under test, with no wait between polls. */
    private HttpAggregatorGateway gateway;

    /** Binds a mock server to a fresh client and builds the gateway on it. */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE).defaultHeader("x-client-id", "cid");
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpAggregatorGateway(builder.build(), new ObjectMapper(), 3, Duration.ZERO);
    }

    /** A consent request posts the range and returns the provider's handle. */
    @Test
    void requestConsentReturnsHandle() {
        server.expect(requestTo(BASE + "/consents")).andExpect(method(HttpMethod.POST))
            .andExpect(header("x-client-id", "cid"))
            .andExpect(content().json("{\"aaHandle\":\"me@aa\",\"dataRange\":{\"from\":\"2025-01-01\",\"to\":\"2025-12-31\"}}"))
            .andRespond(withSuccess("{\"consentHandle\":\"h-1\"}", MediaType.APPLICATION_JSON));

        String handle = gateway.requestConsent("me@aa", List.of("HDFC"), LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 12, 31), "DAILY");

        assertThat(handle).isEqualTo("h-1");
        server.verify();
    }

    /** An approved consent with the matching id yields its expiry. */
    @Test
    void confirmConsentReturnsExpiry() {
        server.expect(requestTo(BASE + "/consents/h-1")).andRespond(withSuccess(
            "{\"status\":\"ACTIVE\",\"consentId\":\"c-1\",\"expiresAt\":\"2026-01-01T00:00:00Z\"}",
            MediaType.APPLICATION_JSON));

        assertThat(gateway.confirmConsent("h-1", "c-1")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    /** A consent that is pending, or approved under another id, is rejected. */
    @Test
    void confirmConsentRejectsPendingAndMismatch() {
        server.expect(requestTo(BASE + "/consents/h-1")).andRespond(withSuccess(
            "{\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.confirmConsent("h-1", "c-1")).isInstanceOf(AggregatorException.class);

        server.reset();
        server.expect(requestTo(BASE + "/consents/h-2")).andRespond(withSuccess(
            "{\"status\":\"ACTIVE\",\"consentId\":\"other\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.confirmConsent("h-2", "c-1")).isInstanceOf(AggregatorException.class);
    }

    /** Revoking posts to the consent; a consent that was never approved needs no call. */
    @Test
    void revokeCallsProviderOnlyForApprovedConsent() {
        server.expect(requestTo(BASE + "/consents/c-1/revoke")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess());

        gateway.revokeConsent("c-1");
        gateway.revokeConsent(null);

        server.verify();
    }

    /** A fetch waits for the session to become ready and converts its payloads, skipping unknown types. */
    @Test
    void fetchPollsUntilReady() {
        server.expect(requestTo(BASE + "/data-sessions")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"sessionId\":\"s-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/data-sessions/s-1")).andRespond(withSuccess(
            "{\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/data-sessions/s-1")).andRespond(withSuccess("""
            {"status":"READY","payloads":[
              {"fipId":"HDFC","accountRef":"XXXX1234","type":"deposit","data":{"transactions":[]}},
              {"fipId":"X","accountRef":"y","type":"MYSTERY","data":{}}]}""", MediaType.APPLICATION_JSON));

        List<AggregatorGateway.FiPayload> payloads = gateway.fetch("c-1", LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 31));

        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0).payloadType()).isEqualTo(PayloadType.DEPOSIT);
        assertThat(payloads.get(0).accountRef()).isEqualTo("XXXX1234");
        assertThat(payloads.get(0).json()).isEqualTo("{\"transactions\":[]}");
    }

    /** A session that never becomes ready, or ends in failure, is an aggregator error. */
    @Test
    void fetchFailsWhenNotReadyOrFailed() {
        server.expect(requestTo(BASE + "/data-sessions")).andRespond(withSuccess(
            "{\"sessionId\":\"s-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/data-sessions/s-1")).andRespond(withSuccess(
            "{\"status\":\"FAILED\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.fetch("c-1", null, null))
            .isInstanceOf(AggregatorException.class).hasMessageContaining("FAILED");

        server.reset();
        server.expect(requestTo(BASE + "/data-sessions")).andRespond(withSuccess(
            "{\"sessionId\":\"s-2\"}", MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.ExpectedCount.times(3), requestTo(BASE + "/data-sessions/s-2"))
            .andRespond(withSuccess("{\"status\":\"PENDING\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.fetch("c-1", null, null))
            .isInstanceOf(AggregatorException.class).hasMessageContaining("not ready");
    }

    /** An HTTP error becomes an {@link AggregatorException} that carries no response body. */
    @Test
    void serverErrorBecomesAggregatorException() {
        server.expect(requestTo(BASE + "/consents")).andRespond(withServerError().body("secret detail"));

        assertThatThrownBy(() -> gateway.requestConsent("me@aa", null, null, null, null))
            .isInstanceOf(AggregatorException.class)
            .hasMessageNotContaining("secret detail");
    }
}
