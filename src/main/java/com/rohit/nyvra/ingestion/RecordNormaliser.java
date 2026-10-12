package com.rohit.nyvra.ingestion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.rohit.nyvra.common.crypto.BlindIndexHasher;
import org.springframework.stereotype.Component;

/**
 * Turns raw provider payloads into {@link TransactionsIngested} events. Deterministic: the same payloads
 * always give the same events and dedup keys. Anything that cannot be mapped is counted in
 * {@link Result#unmapped()} rather than dropped silently.
 *
 * <p>Payload shapes (the provider adapter is responsible for delivering this shape):
 * <ul>
 *   <li>{@code DEPOSIT}: {@code {"transactions":[{"type":"DEBIT|CREDIT","amount":"123.45",
 *       "valueDate":"2025-05-31","narration":"…","currentBalance":"1000.00"}]}} → {@link TransactionsIngested}.</li>
 *   <li>{@code TERM_DEPOSIT}, {@code RECURRING_DEPOSIT}, {@code EPF}, {@code NPS}, {@code LOAN},
 *       {@code CREDIT_CARD}: {@code {"summary":{"<figure>":"1234.00","balanceDateTime":"2025-05-31T10:00:00+05:30"}}}
 *       where {@code <figure>} is {@code currentValue} (term/recurring deposit, NPS), {@code currentBalance} (EPF),
 *       {@code outstandingAmount} (loan) or {@code totalDueAmount} (credit card); {@code balanceDateTime} is
 *       optional → {@link AccountSnapshotIngested}.</li>
 *   <li>{@code MUTUAL_FUND}, {@code EQUITIES}: {@code {"holdings":[{"isin":"…","name":"…","units":"10.5",
 *       "price":"123.45"}]}} (price optional) → {@link HoldingsIngested}.</li>
 * </ul>
 */
@Component
public class RecordNormaliser {

    /** Unmapped reason for payload types this normaliser does not handle yet. */
    static final String UNSUPPORTED_PREFIX = "UNSUPPORTED_";

    /** Unmapped reason for a payload that is not parseable JSON of the expected shape. */
    static final String MALFORMED_PAYLOAD = "MALFORMED_PAYLOAD";

    /** Unmapped reason for a single holding missing or misformatting a required field. */
    static final String INVALID_HOLDING = "INVALID_HOLDING";

    /** Unmapped reason for an account summary missing its figure or carrying an invalid one. */
    static final String INVALID_SUMMARY = "INVALID_SUMMARY";

    /** Summary field that carries the account-level figure, by payload type. */
    private static final Map<PayloadType, String> SUMMARY_FIELD = Map.of(
        PayloadType.TERM_DEPOSIT, "currentValue",
        PayloadType.RECURRING_DEPOSIT, "currentValue",
        PayloadType.NPS, "currentValue",
        PayloadType.EPF, "currentBalance",
        PayloadType.LOAN, "outstandingAmount",
        PayloadType.CREDIT_CARD, "totalDueAmount");

    /** Unmapped reason for a single transaction missing or misformatting a required field. */
    static final String INVALID_TRANSACTION = "INVALID_TRANSACTION";

    /** Parses payload JSON. */
    private final ObjectMapper mapper;

    /** Keyed hasher for dedup keys, so keys leak nothing about amounts or narrations. */
    private final BlindIndexHasher hasher;

    /**
     * Creates the normaliser.
     *
     * @param mapper JSON parser
     * @param hasher keyed hasher for dedup keys
     */
    public RecordNormaliser(ObjectMapper mapper, BlindIndexHasher hasher) {
        this.mapper = mapper;
        this.hasher = hasher;
    }

    /**
     * Normalises one fetch's payloads.
     *
     * @param userId         owner of the data
     * @param fetchSessionId the fetch the payloads belong to
     * @param payloads       the raw payloads
     * @return the events to publish and the counts of what could not be mapped
     */
    public Result normalise(UUID userId, UUID fetchSessionId, List<AggregatorGateway.FiPayload> payloads) {
        List<Object> events = new ArrayList<>();
        Map<String, Integer> unmapped = new TreeMap<>();
        for (AggregatorGateway.FiPayload payload : payloads) {
            PayloadType type = payload.payloadType();
            if (type == PayloadType.DEPOSIT) {
                normaliseDeposit(userId, fetchSessionId, payload, events, unmapped);
            } else if (SUMMARY_FIELD.containsKey(type)) {
                normaliseSummary(userId, fetchSessionId, payload, events, unmapped);
            } else if (type == PayloadType.MUTUAL_FUND || type == PayloadType.EQUITIES) {
                normaliseHoldings(userId, fetchSessionId, payload, events, unmapped);
            } else {
                unmapped.merge(UNSUPPORTED_PREFIX + type, 1, Integer::sum);
            }
        }
        return new Result(List.copyOf(events), unmapped);
    }

    /**
     * Maps an account-level payload to at most one {@link AccountSnapshotIngested}.
     *
     * @param userId         owner of the data
     * @param fetchSessionId the fetch
     * @param payload        the payload
     * @param events         collects the produced event
     * @param unmapped       collects what could not be mapped
     */
    private void normaliseSummary(UUID userId, UUID fetchSessionId, AggregatorGateway.FiPayload payload,
                                  List<Object> events, Map<String, Integer> unmapped) {
        JsonNode summary = parse(payload.json()).path("summary");
        if (summary.isMissingNode() || !summary.isObject()) {
            unmapped.merge(MALFORMED_PAYLOAD, 1, Integer::sum);
            return;
        }
        try {
            BigDecimal amount = new BigDecimal(summary.path(SUMMARY_FIELD.get(payload.payloadType())).asText())
                .setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("negative");
            }
            Instant asOf = summary.hasNonNull("balanceDateTime")
                ? OffsetDateTime.parse(summary.get("balanceDateTime").asText()).toInstant() : null;
            events.add(new AccountSnapshotIngested(userId, fetchSessionId, payload.fipId(), payload.accountRef(),
                payload.payloadType(), amount, asOf));
        } catch (RuntimeException e) {
            unmapped.merge(INVALID_SUMMARY, 1, Integer::sum);
        }
    }

    /**
     * Maps a fund or equity payload to at most one {@link HoldingsIngested}.
     *
     * @param userId         owner of the data
     * @param fetchSessionId the fetch
     * @param payload        the payload
     * @param events         collects the produced event
     * @param unmapped       collects what could not be mapped
     */
    private void normaliseHoldings(UUID userId, UUID fetchSessionId, AggregatorGateway.FiPayload payload,
                                   List<Object> events, Map<String, Integer> unmapped) {
        JsonNode holdings = parse(payload.json()).path("holdings");
        if (!holdings.isArray()) {
            unmapped.merge(MALFORMED_PAYLOAD, 1, Integer::sum);
            return;
        }
        List<IngestedHolding> mapped = new ArrayList<>();
        for (JsonNode node : holdings) {
            try {
                String isin = node.path("isin").asText("").trim();
                BigDecimal units = new BigDecimal(node.path("units").asText()).setScale(6, RoundingMode.HALF_UP);
                if (isin.isEmpty() || units.signum() <= 0) {
                    throw new IllegalArgumentException("isin/units");
                }
                BigDecimal price = node.hasNonNull("price")
                    ? new BigDecimal(node.get("price").asText()).setScale(6, RoundingMode.HALF_UP) : null;
                if (price != null && price.signum() < 0) {
                    throw new IllegalArgumentException("price");
                }
                BigDecimal value = price == null ? null : units.multiply(price).setScale(2, RoundingMode.HALF_UP);
                mapped.add(new IngestedHolding(isin, node.path("name").asText("").trim(), units, price, value));
            } catch (RuntimeException e) {
                unmapped.merge(INVALID_HOLDING, 1, Integer::sum);
            }
        }
        if (!mapped.isEmpty()) {
            events.add(new HoldingsIngested(userId, fetchSessionId, payload.fipId(), payload.accountRef(),
                payload.payloadType(), List.copyOf(mapped)));
        }
    }

    /**
     * Parses payload JSON.
     *
     * @param json the payload text
     * @return the root node, or a missing node when the text is not valid JSON
     */
    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    /**
     * Maps one deposit payload, adding at most one event.
     *
     * @param userId         owner of the data
     * @param fetchSessionId the fetch
     * @param payload        the deposit payload
     * @param events         collects the produced event
     * @param unmapped       collects what could not be mapped
     */
    private void normaliseDeposit(UUID userId, UUID fetchSessionId, AggregatorGateway.FiPayload payload,
                                  List<Object> events, Map<String, Integer> unmapped) {
        JsonNode transactions;
        try {
            transactions = mapper.readTree(payload.json()).path("transactions");
        } catch (Exception e) {
            unmapped.merge(MALFORMED_PAYLOAD, 1, Integer::sum);
            return;
        }
        if (!transactions.isArray()) {
            unmapped.merge(MALFORMED_PAYLOAD, 1, Integer::sum);
            return;
        }
        List<IngestedTransaction> mapped = new ArrayList<>();
        for (JsonNode node : transactions) {
            IngestedTransaction transaction = toTransaction(payload.accountRef(), node);
            if (transaction == null) {
                unmapped.merge(INVALID_TRANSACTION, 1, Integer::sum);
            } else {
                mapped.add(transaction);
            }
        }
        if (!mapped.isEmpty()) {
            events.add(new TransactionsIngested(
                userId, fetchSessionId, payload.fipId(), payload.accountRef(), List.copyOf(mapped)));
        }
    }

    /**
     * Maps one transaction node.
     *
     * @param accountRef provider-side account reference, part of the dedup key
     * @param node       the transaction JSON
     * @return the transaction, or {@code null} when a required field is missing or invalid
     */
    private IngestedTransaction toTransaction(String accountRef, JsonNode node) {
        try {
            IngestedDirection direction = IngestedDirection.valueOf(node.path("type").asText().toUpperCase(Locale.ROOT));
            BigDecimal amount = new BigDecimal(node.path("amount").asText()).setScale(2);
            LocalDate valueDate = LocalDate.parse(node.path("valueDate").asText());
            if (amount.signum() <= 0) {
                return null;
            }
            String narration = node.path("narration").asText("").trim();
            BigDecimal balance = node.hasNonNull("currentBalance")
                ? new BigDecimal(node.get("currentBalance").asText()).setScale(2) : null;
            String dedupKey = hasher.hash(String.join("|",
                String.valueOf(accountRef), valueDate.toString(), amount.toPlainString(), narration));
            return new IngestedTransaction(dedupKey, valueDate, amount, direction, narration, balance);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Outcome of a normalisation pass.
     *
     * @param events   the events to publish: {@link TransactionsIngested}, {@link AccountSnapshotIngested} and
     *                 {@link HoldingsIngested}
     * @param unmapped counts of things that could not be mapped, by reason
     */
    public record Result(List<Object> events, Map<String, Integer> unmapped) {
    }
}
