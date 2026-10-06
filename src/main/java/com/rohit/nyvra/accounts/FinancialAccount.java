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

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private AccountType type;

    @Column(name = "institution")
    private String institution;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "masked_number")
    private String maskedNumber;

    @Column(name = "label")
    private String label;

    // CHAR(3) must be pinned explicitly or ddl-auto=validate sees VARCHAR — see UserProfile.baseCurrency.
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(name = "current_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal currentBalance;

    @Column(name = "balance_as_of", nullable = false)
    private Instant balanceAsOf;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private RecordSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AccountStatus status;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected FinancialAccount() {
        // for JPA
    }

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

    public void updateBalance(Money balance, Instant asOf) {
        getCurrentBalance().requireSameCurrency(balance);
        this.currentBalance = balance.amount();
        this.balanceAsOf = Objects.requireNonNull(asOf, "asOf");
    }

    public void rename(String label) {
        this.label = label;
    }

    public void changeStatus(AccountStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    public void softDelete(Instant at) {
        this.deletedAt = Objects.requireNonNull(at, "at");
    }

    public UUID getUserId() {
        return userId;
    }

    public AccountType getType() {
        return type;
    }

    public String getInstitution() {
        return institution;
    }

    public String getMaskedNumber() {
        return maskedNumber;
    }

    public String getLabel() {
        return label;
    }

    public String getCurrency() {
        return currency;
    }

    public Money getCurrentBalance() {
        return Money.of(currentBalance, currency);
    }

    public Instant getBalanceAsOf() {
        return balanceAsOf;
    }

    public RecordSource getSource() {
        return source;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
