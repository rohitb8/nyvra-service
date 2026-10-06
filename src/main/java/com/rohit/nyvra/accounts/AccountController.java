package com.rohit.nyvra.accounts;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.accounts.dto.AccountResponse;
import com.rohit.nyvra.accounts.dto.CreateAccountRequest;
import com.rohit.nyvra.accounts.dto.UpdateAccountRequest;
import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.persistence.RecordSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** Accounts endpoints per {@code openapi/nyvra-api-v1.yaml}. Thin: all rules live in {@link AccountService}. */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
@SecurityRequirement(name = "keycloak")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List accounts",
        description = "Active and stale accounts by default; pass status=CLOSED to include closed ones.",
        parameters = @Parameter(name = "sort", in = ParameterIn.QUERY, description =
            "`field,asc|desc`, repeatable. Fields: label, type, balanceAsOf, currentBalance. Default `type,asc`.",
            array = @ArraySchema(schema = @Schema(type = "string"))))
    public PageResponse<AccountResponse> list(
            @RequestParam(required = false) List<AccountType> type,
            @RequestParam(required = false) List<AccountStatus> status,
            @RequestParam(required = false) RecordSource source,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        // Read the raw values: binding to List<String> would split "label,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return service.list(type, status, source, page, size, sort == null ? null : List.of(sort));
    }

    @PostMapping
    @Operation(summary = "Create a manual account")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = service.create(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get one account")
    public AccountResponse get(@PathVariable UUID accountId) {
        return service.get(accountId);
    }

    @PatchMapping("/{accountId}")
    @Operation(summary = "Update an account")
    public AccountResponse update(@PathVariable UUID accountId, @Valid @RequestBody UpdateAccountRequest request) {
        return service.update(accountId, request);
    }

    @PostMapping("/{accountId}/close")
    @Operation(summary = "Close an account")
    public AccountResponse close(@PathVariable UUID accountId) {
        return service.close(accountId);
    }

    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a manual account")
    public void delete(@PathVariable UUID accountId) {
        service.delete(accountId);
    }
}
