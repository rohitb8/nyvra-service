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
        TransactionsIngested event = (TransactionsIngested) result.events().get(0);
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
        return ((TransactionsIngested) normaliser.normalise(userId, fetchId, List.of(deposit(txn)))
            .events().get(0)).transactions().get(0).dedupKey();
    }

    /** Malformed payloads and bad transactions are counted, never silently dropped. */
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
            .containsEntry("MALFORMED_PAYLOAD", 3)
            .containsEntry("INVALID_TRANSACTION", 4);
    }

    /** Term deposits, EPF, NPS, loans and cards each become one snapshot using their own summary field. */
    @Test
    void mapsAccountSummariesToSnapshots() {
        List<AggregatorGateway.FiPayload> payloads = List.of(
            new AggregatorGateway.FiPayload("F", "td", PayloadType.TERM_DEPOSIT,
                "{\"summary\":{\"currentValue\":\"100000.456\",\"balanceDateTime\":\"2025-05-31T10:00:00+05:30\"}}"),
            new AggregatorGateway.FiPayload("F", "epf", PayloadType.EPF, "{\"summary\":{\"currentBalance\":\"5\"}}"),
            new AggregatorGateway.FiPayload("F", "ln", PayloadType.LOAN, "{\"summary\":{\"outstandingAmount\":\"7.5\"}}"),
            new AggregatorGateway.FiPayload("F", "cc", PayloadType.CREDIT_CARD, "{\"summary\":{\"totalDueAmount\":\"0\"}}"));

        RecordNormaliser.Result result = normaliser.normalise(userId, fetchId, payloads);

        assertThat(result.unmapped()).isEmpty();
        assertThat(result.events()).hasSize(4).allMatch(e -> e instanceof AccountSnapshotIngested);
        AccountSnapshotIngested td = (AccountSnapshotIngested) result.events().get(0);
        assertThat(td.amount()).isEqualByComparingTo("100000.46");
        assertThat(td.asOf()).isEqualTo(java.time.Instant.parse("2025-05-31T04:30:00Z"));
        assertThat(((AccountSnapshotIngested) result.events().get(2)).amount()).isEqualByComparingTo("7.50");
        assertThat(((AccountSnapshotIngested) result.events().get(1)).asOf()).isNull();
    }

    /** A summary without its figure, with a negative one or with no summary is counted, not dropped. */
    @Test
    void countsBadSummaries() {
        RecordNormaliser.Result result = normaliser.normalise(userId, fetchId, List.of(
            new AggregatorGateway.FiPayload("F", "a", PayloadType.LOAN, "{\"summary\":{}}"),
            new AggregatorGateway.FiPayload("F", "b", PayloadType.LOAN, "{\"summary\":{\"outstandingAmount\":\"-1\"}}"),
            new AggregatorGateway.FiPayload("F", "c", PayloadType.NPS, "{\"x\":1}")));

        assertThat(result.events()).isEmpty();
        assertThat(result.unmapped()).containsEntry("INVALID_SUMMARY", 2).containsEntry("MALFORMED_PAYLOAD", 1);
    }

    /** Fund and equity holdings become one event per payload with units at scale 6 and value at scale 2. */
    @Test
    void mapsHoldings() {
        RecordNormaliser.Result result = normaliser.normalise(userId, fetchId, List.of(
            new AggregatorGateway.FiPayload("CAMS", "folio-1", PayloadType.MUTUAL_FUND, """
                {"holdings":[{"isin":"INF1","name":"Fund A","units":"10.5","price":"123.45"},
                             {"isin":"INF2","name":"Fund B","units":"2"},
                             {"isin":"","units":"1"},
                             {"isin":"INF3","units":"0"}]}""")));

        assertThat(result.unmapped()).containsEntry("INVALID_HOLDING", 2);
        HoldingsIngested event = (HoldingsIngested) result.events().get(0);
        assertThat(event.payloadType()).isEqualTo(PayloadType.MUTUAL_FUND);
        assertThat(event.holdings()).hasSize(2);
        IngestedHolding a = event.holdings().get(0);
        assertThat(a.units()).isEqualByComparingTo("10.500000");
        assertThat(a.units().scale()).isEqualTo(6);
        assertThat(a.value()).isEqualByComparingTo("1296.23");
        assertThat(event.holdings().get(1).price()).isNull();
        assertThat(event.holdings().get(1).value()).isNull();
    }
}
