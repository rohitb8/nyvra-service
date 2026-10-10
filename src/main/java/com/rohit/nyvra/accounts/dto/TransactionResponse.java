package com.rohit.nyvra.accounts.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.rohit.nyvra.accounts.AccountTransaction;
import com.rohit.nyvra.accounts.TransactionDirection;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.common.persistence.RecordSource;

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
    RecordSource source) {

    public static TransactionResponse from(AccountTransaction t) {
        return new TransactionResponse(
            t.getId(), t.getAccountId(), t.getBookingDate(), t.getValueDate(), MoneyDto.from(t.getAmount()),
            t.getDirection(), t.getNarration(), t.getCounterparty(), MoneyDto.from(t.getBalanceAfter()),
            t.getSource());
    }
}
