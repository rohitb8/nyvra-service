package com.rohit.nyvra.accounts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.rohit.nyvra.common.crypto.EncryptedStringConverter;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.AbstractEntity;
import com.rohit.nyvra.common.persistence.RecordSource;

/**
 * A bank, loan, card, deposit or retirement account the user holds. Masked identifier only — never a
 * full account number or credentials (PROJECT_OVERVIEW §4.1). Soft-deleted via {@code deletedAt}.
 */
@Entity
@Table(name = "financial_account")
public class FinancialAccount extends AbstractEntity {

    /** Owning user's id (token {@code sub}); immutable. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Kind of account. */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private AccountType type;

    /** Bank or provider name; nullable. */
    @Column(name = "institution")
    private String institution;

    /** Masked account identifier (encrypted at rest); never a full number; nullable. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "masked_number")
    private String maskedNumber;

    /** User-facing display name; nullable. */
    @Column(name = "label")
    private String label;

    // CHAR(3) must be pinned explicitly or ddl-auto=validate sees VARCHAR — see UserProfile.baseCurrency.
    /** ISO 4217 code of the account, three characters; immutable after creation. */
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** Latest balance in {@link #currency}, scale 2. */
    @Column(name = "current_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal currentBalance;

    /** UTC instant the balance is valid as of. */
    @Column(name = "balance_as_of", nullable = false)
    private Instant balanceAsOf;

    /** How the account entered the system (manual or aggregator). */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RecordSource source;

    /** Lifecycle status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    /** Soft-delete marker; null while the account is live. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Constructor required by JPA. */
    protected FinancialAccount() {
        // for JPA
    }

    /**
     * Creates a new ACTIVE account.
     *
     * @param userId owning user, not null
     * @param type account type, not null
     * @param institution bank or provider name, nullable
     * @param maskedNumber masked identifier, nullable
     * @param label display name, nullable
     * @param currentBalance opening balance; its currency becomes the account currency
     * @param balanceAsOf instant the balance is valid as of, not null
     * @param source record source, not null
     */
    public FinancialAccount(UUID userId, AccountType type, String institution, String maskedNumber,
                            String label, Money currentBalance, Instant balanceAsOf, RecordSource source) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.type = Objects.requireNonNull(type, "type");
        this.institution = institution;
        this.maskedNumber = maskedNumber;
        this.label = label;
        this.currency = currentBalance.currency();
        this.currentBalance = currentBalance.amount();
        this.balanceAsOf = Objects.requireNonNull(balanceAsOf, "balanceAsOf");
        this.source = Objects.requireNonNull(source, "source");
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Replaces the balance.
     *
     * @param balance new balance, must be in the account's currency
     * @param asOf instant the balance is valid as of, not null
     */
    public void updateBalance(Money balance, Instant asOf) {
        getCurrentBalance().requireSameCurrency(balance);
        this.currentBalance = balance.amount();
        this.balanceAsOf = Objects.requireNonNull(asOf, "asOf");
    }

    /**
     * Changes the display name.
     *
     * @param label new label, nullable
     */
    public void rename(String label) {
        this.label = label;
    }

    /**
     * Changes the institution name.
     *
     * @param institution new institution, nullable
     */
    public void changeInstitution(String institution) {
        this.institution = institution;
    }

    /**
     * Sets the lifecycle status.
     *
     * @param status new status, not null
     */
    public void changeStatus(AccountStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Marks the account deleted.
     *
     * @param at deletion instant, not null
     */
    public void softDelete(Instant at) {
        this.deletedAt = Objects.requireNonNull(at, "at");
    }

    /** @return owning user id */
    public UUID getUserId() {
        return userId;
    }

    /** @return account type */
    public AccountType getType() {
        return type;
    }

    /** @return institution name, or null */
    public String getInstitution() {
        return institution;
    }

    /** @return masked identifier, or null */
    public String getMaskedNumber() {
        return maskedNumber;
    }

    /** @return display label, or null */
    public String getLabel() {
        return label;
    }

    /** @return ISO 4217 currency code */
    public String getCurrency() {
        return currency;
    }

    /** @return current balance as money in the account currency */
    public Money getCurrentBalance() {
        return Money.of(currentBalance, currency);
    }

    /** @return instant the balance is valid as of */
    public Instant getBalanceAsOf() {
        return balanceAsOf;
    }

    /** @return record source */
    public RecordSource getSource() {
        return source;
    }

    /** @return lifecycle status */
    public AccountStatus getStatus() {
        return status;
    }

    /** @return deletion instant, or null if live */
    public Instant getDeletedAt() {
        return deletedAt;
    }

    /** @return true if the account has been soft-deleted */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
