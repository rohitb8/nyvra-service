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

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private TransactionDirection direction;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "narration")
    private String narration;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "counterparty")
    private String counterparty;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RecordSource source;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

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

    public LocalDate getValueDate() {
        return valueDate;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getBookingDate() {
        return bookingDate;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public TransactionDirection getDirection() {
        return direction;
    }

    public String getNarration() {
        return narration;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public Money getBalanceAfter() {
        return balanceAfter == null ? null : Money.of(balanceAfter, currency);
    }

    public RecordSource getSource() {
        return source;
    }

    public String getDedupKey() {
        return dedupKey;
    }
}
