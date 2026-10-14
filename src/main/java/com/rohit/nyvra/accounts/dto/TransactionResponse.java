package com.rohit.nyvra.accounts.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.accounts.AccountTransaction;
import com.rohit.nyvra.accounts.TransactionDirection;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.common.persistence.RecordSource;

/**
 * API view of one ledger line.
 *
 * @param id transaction id
 * @param accountId owning account id
 * @param bookingDate date the bank booked the line
 * @param valueDate accounting date
 * @param amount signed amount; debits are negative
 * @param direction debit or credit
 * @param narration bank narration; may be null
 * @param counterparty counterparty name; may be null
 * @param balanceAfter account balance after the line; may be null
 * @param source how the line entered the system
 * @param expenseId id of the linked expense, or null if none
 */
public record TransactionResponse(
    UUID id,
    UUID accountId,
    LocalDate bookingDate,
    LocalDate valueDate,
    MoneyDto amount,
    TransactionDirection direction,
    String narration,
    String counterparty,
    MoneyDto balanceAfter,
    RecordSource source,
    UUID expenseId) {

    /**
     * Maps a ledger line to the API view.
     *
     * @param t the transaction
     * @param expenseId the expense derived from this transaction; {@code null} if none
     * @return the response DTO
     */
    public static TransactionResponse from(AccountTransaction t, UUID expenseId) {
        return new TransactionResponse(
            t.getId(), t.getAccountId(), t.getBookingDate(), t.getValueDate(), MoneyDto.from(t.getAmount()),
            t.getDirection(), t.getNarration(), t.getCounterparty(), MoneyDto.from(t.getBalanceAfter()),
            t.getSource(), expenseId);
    }
}
