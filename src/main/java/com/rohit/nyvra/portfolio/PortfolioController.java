package com.rohit.nyvra.portfolio;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.portfolio.dto.CreateHoldingRequest;
import com.rohit.nyvra.portfolio.dto.CreateInstrumentRequest;
import com.rohit.nyvra.portfolio.dto.HoldingResponse;
import com.rohit.nyvra.portfolio.dto.InstrumentResponse;
import com.rohit.nyvra.portfolio.dto.PortfolioSummaryResponse;
import com.rohit.nyvra.portfolio.dto.QuoteResponse;
import com.rohit.nyvra.portfolio.dto.RecordQuoteRequest;
import com.rohit.nyvra.portfolio.dto.UpdateHoldingRequest;
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

/**
 * REST endpoints for instruments, price quotes, holdings and the portfolio summary under
 * {@code /api/v1/portfolio}. Thin by design: binding and validation happen here, every rule (ownership
 * scoping, currency support, duplicate and read-only checks) lives in {@link PortfolioService}.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
@Tag(name = "Portfolio")
@SecurityRequirement(name = "keycloak")
public class PortfolioController {

    /** Use-case layer every endpoint delegates to. */
    private final PortfolioService service;

    /**
     * Creates the controller.
     *
     * @param service the portfolio use-case service
     */
    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ instruments

    /**
     * Searches the shared instrument catalogue.
     *
     * @param q          text matched against symbol, name and ISIN
     * @param assetClass only this asset class
     * @param page       zero-based page index, at least 0 (default 0)
     * @param size       page size between 1 and 100 (default 20)
     * @return one page of instruments
     */
    @GetMapping("/instruments")
    @Operation(summary = "Search instruments")
    public PageResponse<InstrumentResponse> searchInstruments(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) AssetClass assetClass,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.searchInstruments(q, assetClass, page, size);
    }

    /**
     * Adds an instrument to the shared catalogue.
     *
     * @param request the new instrument
     * @return 201 with the created instrument and a {@code Location} header pointing at it
     */
    @PostMapping("/instruments")
    @Operation(summary = "Add an instrument")
    public ResponseEntity<InstrumentResponse> createInstrument(@Valid @RequestBody CreateInstrumentRequest request) {
        InstrumentResponse created = service.createInstrument(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Returns one instrument.
     *
     * @param instrumentId the instrument id
     * @return the instrument
     */
    @GetMapping("/instruments/{instrumentId}")
    @Operation(summary = "Get one instrument")
    public InstrumentResponse getInstrument(@PathVariable UUID instrumentId) {
        return service.getInstrument(instrumentId);
    }

    /**
     * Records a manual price for an instrument.
     *
     * @param instrumentId the instrument id
     * @param request      the price and optional timestamp
     * @return 201 with the stored quote
     */
    @PostMapping("/instruments/{instrumentId}/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a price for an instrument",
        description = "Quotes are shared by all users and drive current valuation of every holding.")
    public QuoteResponse recordQuote(@PathVariable UUID instrumentId, @Valid @RequestBody RecordQuoteRequest request) {
        return service.recordQuote(instrumentId, request);
    }

    // ------------------------------------------------------------------ holdings

    /**
     * Lists the caller's holdings with their valuation.
     *
     * @param assetClass    only these asset classes; omitted or empty means all
     * @param includeClosed whether closed (zero-quantity) holdings are included (default false)
     * @param page          zero-based page index, at least 0 (default 0)
     * @param size          page size between 1 and 100 (default 20)
     * @param request       the raw request, used to read the repeatable {@code sort} parameter
     * @return one page of holdings; an unknown sort field is rejected with 400
     */
    @GetMapping("/holdings")
    @Operation(summary = "List holdings",
        parameters = @Parameter(name = "sort", in = ParameterIn.QUERY, description =
            "`field,asc|desc`, repeatable. Fields: openedAt, assetClass, createdAt. Default `openedAt,desc`.",
            array = @ArraySchema(schema = @Schema(type = "string"))))
    public PageResponse<HoldingResponse> listHoldings(
            @RequestParam(required = false) List<AssetClass> assetClass,
            @RequestParam(defaultValue = "false") boolean includeClosed,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return service.listHoldings(assetClass, includeClosed, page, size, sortOf(request));
    }

    /**
     * Records a holding manually (source {@code MANUAL}).
     *
     * @param request the new holding
     * @return 201 with the created holding and a {@code Location} header pointing at it
     */
    @PostMapping("/holdings")
    @Operation(summary = "Record a holding manually")
    public ResponseEntity<HoldingResponse> createHolding(@Valid @RequestBody CreateHoldingRequest request) {
        HoldingResponse created = service.createHolding(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Returns one holding with its valuation.
     *
     * @param holdingId the holding id
     * @return the holding; another user's holding is reported as 404
     */
    @GetMapping("/holdings/{holdingId}")
    @Operation(summary = "Get one holding")
    public HoldingResponse getHolding(@PathVariable UUID holdingId) {
        return service.getHolding(holdingId);
    }

    /**
     * Partially updates a holding; omitted fields are unchanged. Quantity zero closes it.
     *
     * @param holdingId the holding id
     * @param request   the fields to change
     * @return the updated holding; another user's holding is reported as 404
     */
    @PatchMapping("/holdings/{holdingId}")
    @Operation(summary = "Update a holding")
    public HoldingResponse updateHolding(@PathVariable UUID holdingId,
                                         @Valid @RequestBody UpdateHoldingRequest request) {
        return service.updateHolding(holdingId, request);
    }

    /**
     * Permanently deletes a holding.
     *
     * @param holdingId the holding id; another user's holding is reported as 404
     */
    @DeleteMapping("/holdings/{holdingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a holding", description = "Only manually entered holdings can be deleted.")
    public void deleteHolding(@PathVariable UUID holdingId) {
        service.deleteHolding(holdingId);
    }

    // ------------------------------------------------------------------ summary

    /**
     * Totals and asset-class allocation across the caller's open INR holdings.
     *
     * @return the portfolio summary
     */
    @GetMapping("/summary")
    @Operation(summary = "Portfolio summary and allocation")
    public PortfolioSummaryResponse summary() {
        return service.summary();
    }

    /**
     * Reads the repeatable {@code sort} query parameter.
     *
     * @param request the current request
     * @return the sort values, or {@code null} when none were sent
     */
    private static List<String> sortOf(HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return sort == null ? null : List.of(sort);
    }
}
