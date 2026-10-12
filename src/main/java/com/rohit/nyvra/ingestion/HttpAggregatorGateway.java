package com.rohit.nyvra.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link AggregatorGateway} for a REST Account Aggregator gateway, selected with
 * {@code nyvra.ingestion.aa.provider=http}. No provider has been chosen yet, so this assumes the common shape of
 * the hosted AA gateways (a thin JSON API in front of the ReBIT consent and data-flow APIs, authenticated with a
 * client id and secret) and keeps every provider-specific detail in this one class. A different provider is a
 * change to the paths and field names below, not to the rest of the application.
 *
 * <p>Assumed API, all under {@code nyvra.ingestion.aa.http.base-url}, authenticated with the headers
 * {@code x-client-id} and {@code x-client-secret}:
 * <ul>
 *   <li>{@code POST /consents} with {@code {"aaHandle","fipList","dataRange":{"from","to"},"frequency"}} →
 *       {@code {"consentHandle"}}.</li>
 *   <li>{@code GET /consents/{consentHandle}} → {@code {"status":"ACTIVE|PENDING|REJECTED","consentId",
 *       "expiresAt"}}.</li>
 *   <li>{@code POST /consents/{consentId}/revoke}.</li>
 *   <li>{@code POST /data-sessions} with {@code {"consentId","dataRange":{"from","to"}}} →
 *       {@code {"sessionId"}}, then {@code GET /data-sessions/{sessionId}} until
 *       {@code {"status":"READY","payloads":[{"fipId","accountRef","type","data":{…}}]}}; {@code type} is a
 *       {@link PayloadType} name and {@code data} is in the shape {@link RecordNormaliser} documents.</li>
 * </ul>
 * Every transport or protocol failure is reported as an {@link AggregatorException} with a message free of
 * personal data and credentials.
 */
@Component
@ConditionalOnProperty(name = "nyvra.ingestion.aa.provider", havingValue = "http")
public class HttpAggregatorGateway implements AggregatorGateway {

    /** Logger. */
    private static final Logger log = LoggerFactory.getLogger(HttpAggregatorGateway.class);

    /** Status of a ready data session. */
    private static final String READY = "READY";

    /** Status of an approved consent. */
    private static final String ACTIVE = "ACTIVE";

    /** HTTP client bound to the gateway's base URL and credentials. */
    private final RestClient client;

    /** Builds request bodies and re-serialises payload data. */
    private final ObjectMapper mapper;

    /** Times to ask for a data session before giving up. */
    private final int maxPolls;

    /** Wait between polls. */
    private final Duration pollInterval;

    /**
     * Creates the gateway from configuration.
     *
     * @param baseUrl      gateway base URL ({@code nyvra.ingestion.aa.http.base-url}), required
     * @param clientId     client id ({@code nyvra.ingestion.aa.http.client-id}), from the environment
     * @param clientSecret client secret ({@code nyvra.ingestion.aa.http.client-secret}), from the environment
     * @param timeout      connect and read timeout ({@code nyvra.ingestion.aa.http.timeout}, default 10s)
     * @param maxPolls     polls of a data session ({@code nyvra.ingestion.aa.http.max-polls}, default 15)
     * @param pollInterval wait between polls ({@code nyvra.ingestion.aa.http.poll-interval}, default 2s)
     * @param mapper       JSON mapper
     */
    public HttpAggregatorGateway(@Value("${nyvra.ingestion.aa.http.base-url}") String baseUrl,
                                 @Value("${nyvra.ingestion.aa.http.client-id}") String clientId,
                                 @Value("${nyvra.ingestion.aa.http.client-secret}") String clientSecret,
                                 @Value("${nyvra.ingestion.aa.http.timeout:10s}") Duration timeout,
                                 @Value("${nyvra.ingestion.aa.http.max-polls:15}") int maxPolls,
                                 @Value("${nyvra.ingestion.aa.http.poll-interval:2s}") Duration pollInterval,
                                 ObjectMapper mapper) {
        this(buildClient(baseUrl, clientId, clientSecret, timeout), mapper, maxPolls, pollInterval);
    }

    /**
     * Creates the gateway around a ready client, for tests.
     *
     * @param client       the HTTP client
     * @param mapper       JSON mapper
     * @param maxPolls     polls of a data session
     * @param pollInterval wait between polls
     */
    HttpAggregatorGateway(RestClient client, ObjectMapper mapper, int maxPolls, Duration pollInterval) {
        this.client = client;
        this.mapper = mapper;
        this.maxPolls = maxPolls;
        this.pollInterval = pollInterval;
    }

    /**
     * Builds the HTTP client.
     *
     * @param baseUrl      gateway base URL
     * @param clientId     client id
     * @param clientSecret client secret
     * @param timeout      connect and read timeout
     * @return the client
     */
    private static RestClient buildClient(String baseUrl, String clientId, String clientSecret, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
            .defaultHeader("x-client-id", clientId).defaultHeader("x-client-secret", clientSecret).build();
    }

    /** {@inheritDoc} */
    @Override
    public String requestConsent(String aaHandle, List<String> fipList, LocalDate from, LocalDate to,
                                 String frequency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aaHandle", aaHandle);
        body.put("fipList", fipList);
        body.put("dataRange", range(from, to));
        body.put("frequency", frequency);
        JsonNode response = call("request a consent", () -> client.post().uri("/consents").body(body)
            .retrieve().body(JsonNode.class));
        String handle = text(response, "consentHandle");
        if (handle == null) {
            throw new AggregatorException("The aggregator did not return a consent handle");
        }
        return handle;
    }

    /** {@inheritDoc} */
    @Override
    public Instant confirmConsent(String consentHandle, String consentId) {
        JsonNode response = call("check the consent", () -> client.get().uri("/consents/{handle}", consentHandle)
            .retrieve().body(JsonNode.class));
        if (!ACTIVE.equalsIgnoreCase(text(response, "status"))) {
            throw new AggregatorException("The consent has not been approved");
        }
        if (!consentId.equals(text(response, "consentId"))) {
            throw new AggregatorException("The consent id does not match the approved consent");
        }
        String expiresAt = text(response, "expiresAt");
        try {
            return expiresAt == null ? null : Instant.parse(expiresAt);
        } catch (RuntimeException e) {
            throw new AggregatorException("The aggregator returned an unreadable consent expiry");
        }
    }

    /** {@inheritDoc} */
    @Override
    public void revokeConsent(String consentId) {
        if (consentId == null) {
            return;
        }
        call("revoke the consent", () -> client.post().uri("/consents/{id}/revoke", consentId)
            .retrieve().toBodilessEntity());
    }

    /** {@inheritDoc} */
    @Override
    public List<FiPayload> fetch(String consentId, LocalDate from, LocalDate to) {
        Map<String, Object> body = Map.of("consentId", consentId, "dataRange", range(from, to));
        JsonNode started = call("start a data session", () -> client.post().uri("/data-sessions").body(body)
            .retrieve().body(JsonNode.class));
        String sessionId = text(started, "sessionId");
        if (sessionId == null) {
            throw new AggregatorException("The aggregator did not return a data session");
        }
        for (int attempt = 1; attempt <= maxPolls; attempt++) {
            JsonNode session = call("read the data session", () -> client.get()
                .uri("/data-sessions/{id}", sessionId).retrieve().body(JsonNode.class));
            String status = text(session, "status");
            if (READY.equalsIgnoreCase(status)) {
                return toPayloads(session.path("payloads"));
            }
            if (!"PENDING".equalsIgnoreCase(status)) {
                throw new AggregatorException("The data session ended with status " + status);
            }
            pause();
        }
        throw new AggregatorException("The data session was not ready in time");
    }

    /**
     * Converts the provider's payload list.
     *
     * @param payloads the {@code payloads} array
     * @return the payloads; entries of an unknown type or without data are skipped
     */
    private List<FiPayload> toPayloads(JsonNode payloads) {
        List<FiPayload> result = new ArrayList<>();
        for (JsonNode node : payloads) {
            PayloadType type;
            try {
                type = PayloadType.valueOf(node.path("type").asText().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warn("Skipping a payload of unknown type {}", node.path("type").asText());
                continue;
            }
            if (!node.hasNonNull("data")) {
                continue;
            }
            result.add(new FiPayload(text(node, "fipId"), text(node, "accountRef"), type, node.get("data").toString()));
        }
        return result;
    }

    /**
     * Builds the {@code dataRange} object.
     *
     * @param from first day, may be {@code null}
     * @param to   last day, may be {@code null}
     * @return the range as a map
     */
    private static Map<String, String> range(LocalDate from, LocalDate to) {
        Map<String, String> range = new LinkedHashMap<>();
        range.put("from", from == null ? null : from.toString());
        range.put("to", to == null ? null : to.toString());
        return range;
    }

    /**
     * Reads a non-blank text field.
     *
     * @param node  the object, may be {@code null}
     * @param field the field name
     * @return the text, or {@code null} when absent or blank
     */
    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || node.get(field).asText().isBlank()) {
            return null;
        }
        return node.get(field).asText();
    }

    /**
     * Runs one HTTP call, turning any client failure into an {@link AggregatorException}.
     *
     * @param action what is being attempted, for the message
     * @param call   the call
     * @param <T>    the result type
     * @return the call's result
     */
    private <T> T call(String action, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientException e) {
            log.warn("Aggregator call failed while trying to {}: {}", action, e.getClass().getSimpleName());
            throw new AggregatorException("The aggregator could not " + action, e);
        }
    }

    /** Waits between polls, restoring the interrupt flag if interrupted. */
    private void pause() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AggregatorException("Interrupted while waiting for the aggregator", e);
        }
    }
}
