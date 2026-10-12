package com.rohit.nyvra.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.nyvra.common.crypto.BlindIndexHasher;
import org.springframework.stereotype.Component;

/**
 * Turns raw provider payloads into {@link TransactionsIngested} events. Deterministic: the same payloads
 * always give the same events and dedup keys. Only {@code DEPOSIT} payloads are mapped today; every other
 * type, and anything malformed, is counted in {@link Result#unmapped()} rather than dropped silently.
 *
 * <p>A deposit payload is {@code {"transactions":[{"type":"DEBIT|CREDIT","amount":"123.45",
 * "valueDate":"2025-05-31","narration":"…","currentBalance":"1000.00"}]}}.
 */
@Component
public class RecordNormaliser {

    /** Unmapped reason for payload types this normaliser does not handle yet. */
    static final String UNSUPPORTED_PREFIX = "UNSUPPORTED_";

    /** Unmapped reason for a payload that is not parseable JSON of the expected shape. */
    static final String MALFORMED_PAYLOAD = "MALFORMED_PAYLOAD";

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
        List<TransactionsIngested> events = new ArrayList<>();
        Map<String, Integer> unmapped = new TreeMap<>();
        for (AggregatorGateway.FiPayload payload : payloads) {
            if (payload.payloadType() != PayloadType.DEPOSIT) {
                unmapped.merge(UNSUPPORTED_PREFIX + payload.payloadType(), 1, Integer::sum);
                continue;
            }
            normaliseDeposit(userId, fetchSessionId, payload, events, unmapped);
        }
        return new Result(List.copyOf(events), unmapped);
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
                                  List<TransactionsIngested> events, Map<String, Integer> unmapped) {
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
     * @param events   the events to publish
     * @param unmapped counts of things that could not be mapped, by reason
     */
    public record Result(List<TransactionsIngested> events, Map<String, Integer> unmapped) {
    }
}
