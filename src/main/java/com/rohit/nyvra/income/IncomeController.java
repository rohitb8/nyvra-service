package com.rohit.nyvra.income;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.income.dto.CreateIncomeEntryRequest;
import com.rohit.nyvra.income.dto.CreateIncomeSourceRequest;
import com.rohit.nyvra.income.dto.IncomeEntryResponse;
import com.rohit.nyvra.income.dto.IncomeSourceResponse;
import com.rohit.nyvra.income.dto.UpdateIncomeEntryRequest;
import com.rohit.nyvra.income.dto.UpdateIncomeSourceRequest;
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

/**
 * REST endpoints for income sources and income entries under {@code /api/v1/income}, as described in
 * {@code openapi/nyvra-api-v1.yaml}. Thin by design: binding and validation happen here, every rule
 * (ownership scoping, overlap checks, read-only detected entries) lives in {@link IncomeService}.
 */
@RestController
@RequestMapping("/api/v1/income")
@Tag(name = "Income")
@SecurityRequirement(name = "keycloak")
public class IncomeController {

    /** Use-case layer every endpoint delegates to. */
    private final IncomeService service;

    /**
     * Creates the controller.
     *
     * @param service the income use-case service
     */
    public IncomeController(IncomeService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ sources

    /**
     * Lists the caller's income sources, paged, optionally filtered by type and active flag.
     *
     * @param type    only sources of these types; omitted or empty means all types
     * @param active  {@code true} for active sources, {@code false} for deactivated ones, omitted for both
     * @param page    zero-based page index, at least 0 (default 0)
     * @param size    page size between 1 and 100 (default 20)
     * @param request the raw request, used to read the repeatable {@code sort} parameter
     * @return one page of sources; an unknown sort field is rejected with 400
     */
    @GetMapping("/sources")
    @Operation(summary = "List income sources",
        parameters = @Parameter(name = "sort", in = ParameterIn.QUERY, description =
            "`field,asc|desc`, repeatable. Fields: name, type, createdAt. Default `name,asc`.",
            array = @ArraySchema(schema = @Schema(type = "string"))))
    public PageResponse<IncomeSourceResponse> listSources(
            @RequestParam(required = false) List<IncomeType> type,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return service.listSources(type, active, page, size, sortOf(request));
    }

    /**
     * Creates an income source for the caller.
     *
     * @param request the new source; {@code expectedAmount} is required unless the cadence is {@code IRREGULAR}
     * @return 201 with the created source and a {@code Location} header pointing at it
     */
    @PostMapping("/sources")
    @Operation(summary = "Create an income source")
    public ResponseEntity<IncomeSourceResponse> createSource(@Valid @RequestBody CreateIncomeSourceRequest request) {
        IncomeSourceResponse created = service.createSource(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Returns one income source.
     *
     * @param sourceId the source id
     * @return the source; another user's source is reported as 404
     */
    @GetMapping("/sources/{sourceId}")
    @Operation(summary = "Get one income source")
    public IncomeSourceResponse getSource(@PathVariable UUID sourceId) {
        return service.getSource(sourceId);
    }

    /**
     * Partially updates an income source; omitted fields are unchanged and an explicit
     * {@code expectedAmount: null} clears the amount.
     *
     * @param sourceId the source id
     * @param request the fields to change
     * @return the updated source; another user's source is reported as 404
     */
    @PatchMapping("/sources/{sourceId}")
    @Operation(summary = "Update an income source")
    public IncomeSourceResponse updateSource(@PathVariable UUID sourceId,
                                             @Valid @RequestBody UpdateIncomeSourceRequest request) {
        return service.updateSource(sourceId, request);
    }

    /**
     * Deletes an income source, or deactivates it when it already has entries so past entries stay explainable.
     *
     * @param sourceId the source id; another user's source is reported as 404
     */
    @DeleteMapping("/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an income source",
        description = "A source with entries is deactivated rather than removed.")
    public void deleteSource(@PathVariable UUID sourceId) {
        service.deleteSource(sourceId);
    }

    // ------------------------------------------------------------------ entries

    /**
     * Lists the caller's income entries, paged, optionally filtered by source, source type and received-on range.
     *
     * @param sourceId only entries of this source
     * @param type     only entries whose source has one of these types
     * @param from     earliest {@code receivedOn} date, inclusive
     * @param to       latest {@code receivedOn} date, inclusive; before {@code from} is rejected with 400
     * @param page     zero-based page index, at least 0 (default 0)
     * @param size     page size between 1 and 100 (default 20)
     * @param request  the raw request, used to read the repeatable {@code sort} parameter
     * @return one page of entries
     */
    @GetMapping("/entries")
    @Operation(summary = "List income entries",
        parameters = @Parameter(name = "sort", in = ParameterIn.QUERY, description =
            "`field,asc|desc`, repeatable. Fields: receivedOn, periodStart, netAmount. Default `receivedOn,desc`.",
            array = @ArraySchema(schema = @Schema(type = "string"))))
    public PageResponse<IncomeEntryResponse> listEntries(
            @RequestParam(required = false) UUID sourceId,
            @RequestParam(required = false) List<IncomeType> type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return service.listEntries(sourceId, type, from, to, page, size, sortOf(request));
    }

    /**
     * Records an income entry manually (origin {@code MANUAL}).
     *
     * @param request the new entry
     * @return 201 with the created entry and a {@code Location} header pointing at it
     */
    @PostMapping("/entries")
    @Operation(summary = "Record an income entry manually")
    public ResponseEntity<IncomeEntryResponse> createEntry(@Valid @RequestBody CreateIncomeEntryRequest request) {
        IncomeEntryResponse created = service.createEntry(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Returns one income entry.
     *
     * @param entryId the entry id
     * @return the entry; another user's entry is reported as 404
     */
    @GetMapping("/entries/{entryId}")
    @Operation(summary = "Get one income entry")
    public IncomeEntryResponse getEntry(@PathVariable UUID entryId) {
        return service.getEntry(entryId);
    }

    /**
     * Partially updates a manually entered income entry; omitted fields are unchanged.
     *
     * @param entryId the entry id
     * @param request the fields to change
     * @return the updated entry; 404 for another user's entry, 409 {@code SOURCE_READ_ONLY} for a detected one
     */
    @PatchMapping("/entries/{entryId}")
    @Operation(summary = "Update an income entry")
    public IncomeEntryResponse updateEntry(@PathVariable UUID entryId,
                                           @Valid @RequestBody UpdateIncomeEntryRequest request) {
        return service.updateEntry(entryId, request);
    }

    /**
     * Deletes a manually entered income entry.
     *
     * @param entryId the entry id; 404 for another user's entry, 409 {@code SOURCE_READ_ONLY} for a detected one
     */
    @DeleteMapping("/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an income entry")
    public void deleteEntry(@PathVariable UUID entryId) {
        service.deleteEntry(entryId);
    }

    /**
     * Reads the raw {@code sort} values: binding to {@code List<String>} would split {@code field,asc} on the comma.
     *
     * @param request the current request
     * @return the sort values, or {@code null} when none were sent
     */
    private static List<String> sortOf(HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return sort == null ? null : List.of(sort);
    }
}
