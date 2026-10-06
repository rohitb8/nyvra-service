package com.rohit.nyvra.accounts.dto;

import java.time.Instant;
import java.util.UUID;

import com.rohit.nyvra.accounts.AccountStatus;
import com.rohit.nyvra.accounts.AccountType;
import com.rohit.nyvra.accounts.CardDetail;
import com.rohit.nyvra.accounts.CardNetwork;
import com.rohit.nyvra.accounts.FinancialAccount;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.common.persistence.RecordSource;

public record AccountResponse(
    UUID id,
    AccountType type,
    String institution,
    String maskedNumber,
    String label,
    String currency,
    MoneyDto currentBalance,
    Instant balanceAsOf,
    RecordSource source,
    AccountStatus status,
    Card card) {

    public record Card(String last4, CardNetwork network, String label, MoneyDto creditLimit) {

        static Card from(CardDetail card) {
            return card == null ? null
                : new Card(card.getLast4(), card.getNetwork(), card.getLabel(), MoneyDto.from(card.getCreditLimit()));
        }
    }

    /** @param card the account's card details, or {@code null} for non-card accounts */
    public static AccountResponse from(FinancialAccount account, CardDetail card) {
        return new AccountResponse(
            account.getId(), account.getType(), account.getInstitution(), account.getMaskedNumber(),
            account.getLabel(), account.getCurrency(), MoneyDto.from(account.getCurrentBalance()),
            account.getBalanceAsOf(), account.getSource(), account.getStatus(), Card.from(card));
    }
}
