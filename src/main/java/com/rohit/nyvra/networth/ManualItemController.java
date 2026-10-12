package com.rohit.nyvra.networth;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.networth.dto.CreateManualItemRequest;
import com.rohit.nyvra.networth.dto.ManualItemResponse;
import com.rohit.nyvra.networth.dto.UpdateManualItemRequest;

/** Endpoints for hand-maintained assets and liabilities. Thin: all rules live in {@link ManualItemService}. */
@RestController
@RequestMapping("/api/v1/net-worth/manual-items")
@Tag(name = "Net worth")
@SecurityRequirement(name = "keycloak")
public class ManualItemController {

    /** The use cases behind the endpoints. */
    private final ManualItemService service;

    /**
     * Creates the controller.
     *
     * @param service the manual item service
     */
    public ManualItemController(ManualItemService service) {
        this.service = service;
    }

    /**
     * Lists entries.
     *
     * @param kind optional filter
     * @param page zero-based page
     * @param size page size
     * @return the page
     */
    @GetMapping
    @Operation(summary = "List manual assets and liabilities")
    public PageResponse<ManualItemResponse> list(
            @RequestParam(required = false) ManualItemKind kind,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(kind, page, size);
    }

    /**
     * Adds an entry.
     *
     * @param request the new entry
     * @return {@code 201} with the entry
     */
    @PostMapping
    @Operation(summary = "Add a manual asset or liability")
    public ResponseEntity<ManualItemResponse> create(@Valid @RequestBody CreateManualItemRequest request) {
        ManualItemResponse created = service.create(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Returns one entry.
     *
     * @param itemId the entry id
     * @return the entry
     */
    @GetMapping("/{itemId}")
    @Operation(summary = "Get one manual asset or liability")
    public ManualItemResponse get(@PathVariable UUID itemId) {
        return service.get(itemId);
    }

    /**
     * Updates an entry.
     *
     * @param itemId  the entry id
     * @param request the changes
     * @return the updated entry
     */
    @PatchMapping("/{itemId}")
    @Operation(summary = "Update a manual asset or liability")
    public ManualItemResponse update(@PathVariable UUID itemId, @Valid @RequestBody UpdateManualItemRequest request) {
        return service.update(itemId, request);
    }

    /**
     * Deletes an entry.
     *
     * @param itemId the entry id
     */
    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a manual asset or liability")
    public void delete(@PathVariable UUID itemId) {
        service.delete(itemId);
    }
}
