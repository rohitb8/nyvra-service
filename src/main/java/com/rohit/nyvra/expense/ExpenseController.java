package com.rohit.nyvra.expense;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.api.CursorPage;
import com.rohit.nyvra.expense.dto.CreateExpenseRequest;
import com.rohit.nyvra.expense.dto.ExpenseResponse;
import com.rohit.nyvra.expense.dto.SplitExpenseRequest;
import com.rohit.nyvra.expense.dto.UpdateExpenseRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Expense endpoints per {@code openapi/nyvra-api-v1.yaml}. Thin: all rules live in {@link ExpenseService}. */
@RestController
@RequestMapping("/api/v1/expenses")
@Tag(name = "Expenses")
@SecurityRequirement(name = "keycloak")
public class ExpenseController {

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List expenses",
        description = "Newest first. Cursor-paginated. Pass either `month` or `from`/`to`. Split parents carry their `splits`.")
    public CursorPage<ExpenseResponse> list(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) List<Necessity> necessity,
            @RequestParam(required = false) ExpenseOrigin origin,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return service.list(month, from, to, categoryId, necessity, origin, q, cursor, limit);
    }

    @PostMapping
    @Operation(summary = "Record a manual expense")
    public ResponseEntity<ExpenseResponse> create(@Valid @RequestBody CreateExpenseRequest request) {
        ExpenseResponse created = service.create(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    @GetMapping("/{expenseId}")
    @Operation(summary = "Get one expense")
    public ExpenseResponse get(@PathVariable UUID expenseId) {
        return service.get(expenseId);
    }

    @PatchMapping("/{expenseId}")
    @Operation(summary = "Recategorise or edit an expense",
        description = "Amount and date only on MANUAL expenses (`409 SOURCE_READ_ONLY` otherwise).")
    public ExpenseResponse update(@PathVariable UUID expenseId, @Valid @RequestBody UpdateExpenseRequest request) {
        return service.update(expenseId, request);
    }

    @DeleteMapping("/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a manual expense")
    public void delete(@PathVariable UUID expenseId) {
        service.delete(expenseId);
    }

    @PostMapping("/{expenseId}/split")
    @Operation(summary = "Split an expense",
        description = "2–20 parts summing exactly to the expense (`422 SPLIT_SUM_MISMATCH`); already split → `409 ALREADY_SPLIT`.")
    public ExpenseResponse split(@PathVariable UUID expenseId, @Valid @RequestBody SplitExpenseRequest request) {
        return service.split(expenseId, request);
    }

    @DeleteMapping("/{expenseId}/split")
    @Operation(summary = "Undo a split")
    public ExpenseResponse unsplit(@PathVariable UUID expenseId) {
        return service.unsplit(expenseId);
    }
}
