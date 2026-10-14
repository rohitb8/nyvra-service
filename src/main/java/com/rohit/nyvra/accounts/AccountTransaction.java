package com.rohit.nyvra.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rohit.nyvra.common.crypto.EncryptedStringConverter;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.AbstractEntity;
import com.rohit.nyvra.common.persistence.RecordSource;

/**
 * One ledger line on a {@link FinancialAccount} (table {@code transaction}; named to avoid clashing with
 * the many {@code Transaction} types on the classpath). Immutable once persisted — corrections are new
 * reversing rows. {@code amount} is signed: debits negative, credits positive.
 *
 * <p>The table is monthly-partitioned by {@code value_date} and its real primary key is
 * {@code (id, value_date)}; {@code id} alone is still unique (UUID v7), so it is mapped as the JPA id.
 * Callers writing to a month that may lack a partition call
 * {@link com.rohit.nyvra.common.partition.MonthlyPartitions#ensureRange} first.
 */
@Entity
@Immutable
@Table(name = "transaction")
public class AccountTransaction extends AbstractEntity {

    /** Accounting date; partition key. */
    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    /** Id of the {@link FinancialAccount} this line belongs to. */
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Owning user's id. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Date the bank booked the line. */
    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    /** Signed amount in {@link #currency}, scale 2; debits negative. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** ISO 4217 code, three characters. */
    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** DEBIT or CREDIT, derived from the sign of the amount. */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private TransactionDirection direction;

    /** Bank narration (encrypted at rest); nullable. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "narration")
    private String narration;

    /** Counterparty name (encrypted at rest); nullable. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "counterparty")
    private String counterparty;

    /** Account balance after this line in {@link #currency}, scale 2; nullable. */
    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    /** How the line entered the system. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RecordSource source;

    /** Keyed hash used to skip duplicate imports. */
    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    /** Constructor required by JPA. */
    protected AccountTransaction() {
        // for JPA
    }

    /**
     * @param balanceAfter nullable; must share {@code amount}'s currency
     * @param dedupKey     keyed hash of (accountRef, valueDate, amount, narration) — build it with
     *                     {@link com.rohit.nyvra.common.crypto.BlindIndexHasher}, never a plain hash of
     *                     the plaintext narration
     */
    public AccountTransaction(UUID accountId, UUID userId, LocalDate bookingDate, LocalDate valueDate,
                              Money amount, String narration, String counterparty, Money balanceAfter,
                              RecordSource source, String dedupKey) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.bookingDate = Objects.requireNonNull(bookingDate, "bookingDate");
        this.valueDate = Objects.requireNonNull(valueDate, "valueDate");
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.direction = amount.isNegative() ? TransactionDirection.DEBIT : TransactionDirection.CREDIT;
        this.narration = narration;
        this.counterparty = counterparty;
        if (balanceAfter != null) {
            amount.requireSameCurrency(balanceAfter);
            this.balanceAfter = balanceAfter.amount();
        }
        this.source = Objects.requireNonNull(source, "source");
        this.dedupKey = Objects.requireNonNull(dedupKey, "dedupKey");
    }

    /** @return accounting date */
    public LocalDate getValueDate() {
        return valueDate;
    }

    /** @return owning account id */
    public UUID getAccountId() {
        return accountId;
    }

    /** @return owning user id */
    public UUID getUserId() {
        return userId;
    }

    /** @return bank booking date */
    public LocalDate getBookingDate() {
        return bookingDate;
    }

    /** @return signed amount (debits negative) */
    public Money getAmount() {
        return Money.of(amount, currency);
    }

    /** @return debit or credit */
    public TransactionDirection getDirection() {
        return direction;
    }

    /** @return narration, or null */
    public String getNarration() {
        return narration;
    }

    /** @return counterparty, or null */
    public String getCounterparty() {
        return counterparty;
    }

    /** @return balance after this line, or null if unknown */
    public Money getBalanceAfter() {
        return balanceAfter == null ? null : Money.of(balanceAfter, currency);
    }

    /** @return record source */
    public RecordSource getSource() {
        return source;
    }

    /** @return deduplication key */
    public String getDedupKey() {
        return dedupKey;
    }
}
