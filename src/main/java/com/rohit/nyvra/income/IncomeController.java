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

/** Income endpoints per {@code openapi/nyvra-api-v1.yaml}. Thin: all rules live in {@link IncomeService}. */
@RestController
@RequestMapping("/api/v1/income")
@Tag(name = "Income")
@SecurityRequirement(name = "keycloak")
public class IncomeController {

    private final IncomeService service;

    public IncomeController(IncomeService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ sources

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

    @PostMapping("/sources")
    @Operation(summary = "Create an income source")
    public ResponseEntity<IncomeSourceResponse> createSource(@Valid @RequestBody CreateIncomeSourceRequest request) {
        IncomeSourceResponse created = service.createSource(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    @GetMapping("/sources/{sourceId}")
    @Operation(summary = "Get one income source")
    public IncomeSourceResponse getSource(@PathVariable UUID sourceId) {
        return service.getSource(sourceId);
    }

    @PatchMapping("/sources/{sourceId}")
    @Operation(summary = "Update an income source")
    public IncomeSourceResponse updateSource(@PathVariable UUID sourceId,
                                             @Valid @RequestBody UpdateIncomeSourceRequest request) {
        return service.updateSource(sourceId, request);
    }

    @DeleteMapping("/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an income source",
        description = "A source with entries is deactivated rather than removed.")
    public void deleteSource(@PathVariable UUID sourceId) {
        service.deleteSource(sourceId);
    }

    // ------------------------------------------------------------------ entries

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

    @PostMapping("/entries")
    @Operation(summary = "Record an income entry manually")
    public ResponseEntity<IncomeEntryResponse> createEntry(@Valid @RequestBody CreateIncomeEntryRequest request) {
        IncomeEntryResponse created = service.createEntry(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    @GetMapping("/entries/{entryId}")
    @Operation(summary = "Get one income entry")
    public IncomeEntryResponse getEntry(@PathVariable UUID entryId) {
        return service.getEntry(entryId);
    }

    @PatchMapping("/entries/{entryId}")
    @Operation(summary = "Update an income entry")
    public IncomeEntryResponse updateEntry(@PathVariable UUID entryId,
                                           @Valid @RequestBody UpdateIncomeEntryRequest request) {
        return service.updateEntry(entryId, request);
    }

    @DeleteMapping("/entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an income entry")
    public void deleteEntry(@PathVariable UUID entryId) {
        service.deleteEntry(entryId);
    }

    /** Read the raw values: binding to List<String> would split "field,asc" on the comma. */
    private static List<String> sortOf(HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return sort == null ? null : List.of(sort);
    }
}
