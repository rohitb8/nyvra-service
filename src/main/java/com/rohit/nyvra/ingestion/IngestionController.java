package com.rohit.nyvra.ingestion;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.ingestion.dto.ActivateConsentRequest;
import com.rohit.nyvra.ingestion.dto.ConsentResponse;
import com.rohit.nyvra.ingestion.dto.CreateConsentRequest;
import com.rohit.nyvra.ingestion.dto.FetchSessionResponse;
import com.rohit.nyvra.ingestion.dto.TriggerFetchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Ingestion endpoints per {@code openapi/nyvra-api-v1.yaml}: Account Aggregator consents and the fetches run
 * under them. Thin: rules live in {@link ConsentService} and {@link FetchService}. Raw provider data is never
 * exposed here.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
@Tag(name = "Ingestion")
@SecurityRequirement(name = "keycloak")
public class IngestionController {

    /** Consent use cases. */
    private final ConsentService consents;

    /** Fetch use cases. */
    private final FetchService fetches;

    /**
     * Creates the controller.
     *
     * @param consents consent use cases
     * @param fetches  fetch use cases
     */
    public IngestionController(ConsentService consents, FetchService fetches) {
        this.consents = consents;
        this.fetches = fetches;
    }

    /**
     * Lists consents.
     *
     * @param status filter by state, repeatable
     * @param page   zero-based page
     * @param size   page size
     * @return the page, newest first
     */
    @GetMapping("/consents")
    @Operation(summary = "List aggregator consents")
    public PageResponse<ConsentResponse> listConsents(@RequestParam(required = false) List<ConsentStatus> status,
                                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return consents.list(status, page, size);
    }

    /**
     * Starts a consent request.
     *
     * @param request the consent details
     * @return 201 with the consent in {@code REQUESTED}
     */
    @PostMapping("/consents")
    @Operation(summary = "Request an aggregator consent")
    public ResponseEntity<ConsentResponse> createConsent(@Valid @RequestBody CreateConsentRequest request) {
        ConsentResponse created = consents.create(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Returns one consent.
     *
     * @param consentId the consent id
     * @return the consent
     */
    @GetMapping("/consents/{consentId}")
    @Operation(summary = "Get one consent")
    public ConsentResponse getConsent(@PathVariable UUID consentId) {
        return consents.get(consentId);
    }

    /**
     * Activates a consent the user approved at the aggregator.
     *
     * @param consentId the consent id
     * @param request   the approved consent artefact id
     * @return the active consent
     */
    @PostMapping("/consents/{consentId}/activate")
    @Operation(summary = "Activate an approved consent")
    public ConsentResponse activateConsent(@PathVariable UUID consentId,
                                           @Valid @RequestBody ActivateConsentRequest request) {
        return consents.activate(consentId, request);
    }

    /**
     * Pauses an active consent.
     *
     * @param consentId the consent id
     * @return the paused consent
     */
    @PostMapping("/consents/{consentId}/pause")
    @Operation(summary = "Pause a consent")
    public ConsentResponse pauseConsent(@PathVariable UUID consentId) {
        return consents.pause(consentId);
    }

    /**
     * Resumes a paused consent.
     *
     * @param consentId the consent id
     * @return the active consent
     */
    @PostMapping("/consents/{consentId}/resume")
    @Operation(summary = "Resume a paused consent")
    public ConsentResponse resumeConsent(@PathVariable UUID consentId) {
        return consents.resume(consentId);
    }

    /**
     * Revokes a consent.
     *
     * @param consentId the consent id
     * @return the revoked consent
     */
    @PostMapping("/consents/{consentId}/revoke")
    @Operation(summary = "Revoke a consent")
    public ConsentResponse revokeConsent(@PathVariable UUID consentId) {
        return consents.revoke(consentId);
    }

    /**
     * Fetches data under a consent. Idempotent per consent and range.
     *
     * @param consentId the consent id
     * @param request   optional range
     * @return 202 for a new fetch, 200 with the existing session when the same range was fetched before
     */
    @PostMapping("/consents/{consentId}/fetches")
    @Operation(summary = "Fetch data under a consent",
        description = "202 when a new fetch ran, 200 with the existing session if this consent and range were already fetched.")
    public ResponseEntity<FetchSessionResponse> triggerFetch(@PathVariable UUID consentId,
                                                             @Valid @RequestBody(required = false)
                                                             TriggerFetchRequest request) {
        FetchService.TriggerResult result = fetches.trigger(consentId, request);
        HttpStatus status = result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status)
            .header("Location", "/api/v1/ingestion/fetches/" + result.session().id())
            .body(result.session());
    }

    /**
     * Lists fetch sessions.
     *
     * @param consentId filter by consent
     * @param status    filter by state, repeatable
     * @param page      zero-based page
     * @param size      page size
     * @return the page, newest first
     */
    @GetMapping("/fetches")
    @Operation(summary = "List fetch sessions")
    public PageResponse<FetchSessionResponse> listFetches(@RequestParam(required = false) UUID consentId,
                                                          @RequestParam(required = false) List<FetchStatus> status,
                                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return fetches.list(consentId, status, page, size);
    }

    /**
     * Returns one fetch session.
     *
     * @param fetchId the session id
     * @return the session with its latest normalisation summary
     */
    @GetMapping("/fetches/{fetchId}")
    @Operation(summary = "Get one fetch session")
    public FetchSessionResponse getFetch(@PathVariable UUID fetchId) {
        return fetches.get(fetchId);
    }

    /**
     * Normalises a completed fetch again from its stored raw records.
     *
     * @param fetchId the session id
     * @return the session with the new normalisation summary
     */
    @PostMapping("/fetches/{fetchId}/normalise")
    @Operation(summary = "Re-run normalisation for a fetch")
    public FetchSessionResponse renormalise(@PathVariable UUID fetchId) {
        return fetches.renormalise(fetchId);
    }
}
