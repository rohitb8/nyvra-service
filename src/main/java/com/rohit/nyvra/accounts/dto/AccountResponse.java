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

/**
 * API view of an account.
 *
 * @param id account id
 * @param type account type
 * @param institution bank or provider name; may be null
 * @param maskedNumber masked account identifier; may be null
 * @param label display name
 * @param currency ISO 4217 currency code
 * @param currentBalance latest balance (scale 2, in {@code currency})
 * @param balanceAsOf UTC instant the balance is valid as of
 * @param source how the account entered the system
 * @param status lifecycle status
 * @param card card details, or null for non-card accounts
 */
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

    /**
     * Card display details.
     *
     * @param last4 last four digits
     * @param network card network
     * @param label card label; may be null
     * @param creditLimit credit limit; may be null
     */
    public record Card(String last4, CardNetwork network, String label, MoneyDto creditLimit) {

        /**
         * Maps a card entity.
         *
         * @param card card entity, may be null
         * @return the DTO, or null when {@code card} is null
         */
        static Card from(CardDetail card) {
            return card == null ? null
                : new Card(card.getLast4(), card.getNetwork(), card.getLabel(), MoneyDto.from(card.getCreditLimit()));
        }
    }

    /**
     * Maps an account and its card to the API view.
     *
     * @param account the account
     * @param card the account's card details, or {@code null} for non-card accounts
     * @return the response DTO
     */
    public static AccountResponse from(FinancialAccount account, CardDetail card) {
        return new AccountResponse(
            account.getId(), account.getType(), account.getInstitution(), account.getMaskedNumber(),
            account.getLabel(), account.getCurrency(), MoneyDto.from(account.getCurrentBalance()),
            account.getBalanceAsOf(), account.getSource(), account.getStatus(), Card.from(card));
    }
}
