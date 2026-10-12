package com.rohit.nyvra.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit.nyvra.common.crypto.BlindIndexHasher;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RecordNormaliser}: mapping, dedup-key stability and the unmapped counters. */
class RecordNormaliserTest {

    /** Hasher with a throw-away all-zero key; only determinism matters here. */
    private final BlindIndexHasher hasher = new BlindIndexHasher(new byte[32]);

    /** The unit under test. */
    private final RecordNormaliser normaliser = new RecordNormaliser(new ObjectMapper(), hasher);

    /** Owner used for every call. */
    private final UUID userId = UUID.randomUUID();

    /** Fetch used for every call. */
    private final UUID fetchId = UUID.randomUUID();

    /**
     * Builds a deposit payload.
     *
     * @param transactions the JSON array body of {@code transactions}
     * @return the payload
     */
    private static AggregatorGateway.FiPayload deposit(String transactions) {
        return new AggregatorGateway.FiPayload("HDFC", "acct-1", PayloadType.DEPOSIT,
            "{\"transactions\":[" + transactions + "]}");
    }

    /** A valid deposit becomes one event with signed-free positive amounts, scale 2 and a direction. */
    @Test
    void mapsDepositTransactionsToOneEventPerAccount() {
        RecordNormaliser.Result result = normaliser.normalise(userId, fetchId, List.of(deposit("""
            {"type":"DEBIT","amount":"250.5","valueDate":"2025-05-31","narration":" UPI-Swiggy ","currentBalance":"1000"},
            {"type":"credit","amount":"85000","valueDate":"2025-05-30","narration":"SALARY"}""")));

        assertThat(result.unmapped()).isEmpty();
        assertThat(result.events()).hasSize(1);
        TransactionsIngested event = result.events().get(0);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.fipId()).isEqualTo("HDFC");
        assertThat(event.transactions()).extracting(IngestedTransaction::direction)
            .containsExactly(IngestedDirection.DEBIT, IngestedDirection.CREDIT);
        IngestedTransaction first = event.transactions().get(0);
        assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("250.50"));
        assertThat(first.amount().scale()).isEqualTo(2);
        assertThat(first.narration()).isEqualTo("UPI-Swiggy");
        assertThat(first.valueDate()).isEqualTo(LocalDate.of(2025, 5, 31));
        assertThat(first.balanceAfter()).isEqualByComparingTo("1000.00");
        assertThat(event.transactions().get(1).balanceAfter()).isNull();
    }

    /** The same payload twice yields the same keys, and a different amount a different key. */
    @Test
    void dedupKeysAreStableAndSensitiveToTheirInputs() {
        String txn = "{\"type\":\"DEBIT\",\"amount\":\"10.00\",\"valueDate\":\"2025-05-31\",\"narration\":\"A\"}";
        String other = txn.replace("10.00", "11.00");

        String a = key(txn);
        assertThat(key(txn)).isEqualTo(a);
        assertThat(key(other)).isNotEqualTo(a);
    }

    /**
     * Normalises one transaction and returns its dedup key.
     *
     * @param txn the transaction JSON
     * @return its dedup key
     */
    private String key(String txn) {
        return normaliser.normalise(userId, fetchId, List.of(deposit(txn)))
            .events().get(0).transactions().get(0).dedupKey();
    }

    /** Other FI types, malformed payloads and bad transactions are counted, never silently dropped. */
    @Test
    void countsWhatItCannotMap() {
        RecordNormaliser.Result result = normaliser.normalise(userId, fetchId, List.of(
            new AggregatorGateway.FiPayload("X", "a", PayloadType.MUTUAL_FUND, "{}"),
            new AggregatorGateway.FiPayload("X", "b", PayloadType.DEPOSIT, "not json"),
            new AggregatorGateway.FiPayload("X", "c", PayloadType.DEPOSIT, "{\"transactions\":{}}"),
            deposit("""
                {"type":"DEBIT","amount":"-5","valueDate":"2025-05-31"},
                {"type":"SIDEWAYS","amount":"5","valueDate":"2025-05-31"},
                {"type":"DEBIT","amount":"1.234","valueDate":"2025-05-31"},
                {"type":"DEBIT","amount":"5","valueDate":"31/05/2025"}""")));

        assertThat(result.events()).isEmpty();
        assertThat(result.unmapped())
            .containsEntry("UNSUPPORTED_MUTUAL_FUND", 1)
            .containsEntry("MALFORMED_PAYLOAD", 2)
            .containsEntry("INVALID_TRANSACTION", 4);
    }
}
