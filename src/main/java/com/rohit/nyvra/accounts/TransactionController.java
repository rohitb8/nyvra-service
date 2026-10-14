package com.rohit.nyvra.accounts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.accounts.dto.TransactionResponse;
import com.rohit.nyvra.common.api.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only ledger endpoints per {@code openapi/nyvra-api-v1.yaml}. Transactions are immutable. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Transactions")
@SecurityRequirement(name = "keycloak")
public class TransactionController {

    /** Read-side ledger service. */
    private final TransactionService service;

    /**
     * Creates the controller.
     *
     * @param service transaction read service
     */
    public TransactionController(TransactionService service) {
        this.service = service;
    }

    /**
     * Lists transactions across the caller's accounts, newest first.
     *
     * @param accountId optional account ids to restrict to
     * @param from optional inclusive start value date
     * @param to optional inclusive end value date
     * @param direction optional debit/credit filter
     * @param cursor opaque cursor from the previous page
     * @param limit page size, 1 to 200
     * @return one cursor page
     */
    @GetMapping("/transactions")
    @Operation(summary = "List transactions across accounts", description = "Newest first. Cursor-paginated.")
    public CursorPage<TransactionResponse> list(
            @RequestParam(required = false) List<UUID> accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransactionDirection direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.list(accountId, from, to, direction, cursor, limit);
    }

    /**
     * Lists one account's transactions, newest first.
     *
     * @param accountId account id
     * @param from optional inclusive start value date
     * @param to optional inclusive end value date
     * @param direction optional debit/credit filter
     * @param cursor opaque cursor from the previous page
     * @param limit page size, 1 to 200
     * @return one cursor page
     */
    @GetMapping("/accounts/{accountId}/transactions")
    @Operation(summary = "List one account's transactions", description = "Newest first. Cursor-paginated.")
    public CursorPage<TransactionResponse> listForAccount(
            @PathVariable UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) TransactionDirection direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.listForAccount(accountId, from, to, direction, cursor, limit);
    }

    /**
     * Returns one transaction.
     *
     * @param transactionId transaction id
     * @return the transaction; 404 if not the caller's
     */
    @GetMapping("/transactions/{transactionId}")
    @Operation(summary = "Get one transaction",
        description = "Transactions are immutable; corrections arrive as new reversing transactions.")
    public TransactionResponse get(@PathVariable UUID transactionId) {
        return service.get(transactionId);
    }
}
