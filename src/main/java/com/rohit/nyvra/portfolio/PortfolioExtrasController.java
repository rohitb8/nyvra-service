package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.portfolio.dto.ConversionResponse;
import com.rohit.nyvra.portfolio.dto.FxRateResponse;
import com.rohit.nyvra.portfolio.dto.PriceFeedSyncResponse;
import com.rohit.nyvra.portfolio.dto.RecordFxRateRequest;
import com.rohit.nyvra.portfolio.dto.ReturnsResponse;
import com.rohit.nyvra.portfolio.dto.ValuationHistoryResponse;
import com.rohit.nyvra.portfolio.dto.ValuationHistoryResponse.Interval;
import com.rohit.nyvra.portfolio.feed.PriceFeedSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portfolio extras: annualised returns, valuation history, FX rates and conversion, and the administrator-only
 * price feed sync. Kept in its own controller so the original portfolio endpoints stay untouched.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
@Tag(name = "Portfolio")
@SecurityRequirement(name = "keycloak")
public class PortfolioExtrasController {

    /** Returns and history. */
    private final PortfolioAnalyticsService analytics;

    /** FX rates and conversion. */
    private final FxRateService fx;

    /** Pulls from the price feed. */
    private final PriceFeedSyncService feedSync;

    /**
     * Creates the controller.
     *
     * @param analytics returns and history
     * @param fx        FX rates and conversion
     * @param feedSync  pulls from the price feed
     */
    public PortfolioExtrasController(PortfolioAnalyticsService analytics, FxRateService fx,
                                     PriceFeedSyncService feedSync) {
        this.analytics = analytics;
        this.fx = fx;
        this.feedSync = feedSync;
    }

    /**
     * Annualised return of the caller's open holdings.
     *
     * @return overall and per-holding XIRR
     */
    @GetMapping("/returns")
    @Operation(summary = "Annualised return (XIRR)",
        description = "Treats each open INR holding as its cost paid on the opening date and its current value "
            + "received now. Holdings without a cost or a price are excluded and counted.")
    public ReturnsResponse returns() {
        return analytics.returns();
    }

    /**
     * The caller's portfolio value over time.
     *
     * @param from     first day, inclusive; defaults to 29 days before {@code to}
     * @param to       last day, inclusive; defaults to today
     * @param interval DAILY (default), WEEKLY or MONTHLY
     * @return dated values, oldest first
     */
    @GetMapping("/valuation-history")
    @Operation(summary = "Portfolio value over time",
        description = "Values today's open holdings with the price in force at the end of each day. At most "
            + "400 points per response.")
    public ValuationHistoryResponse valuationHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Interval interval) {
        return analytics.valuationHistory(from, to, interval);
    }

    /**
     * Lists a currency's rates.
     *
     * @param currency ISO 4217 code
     * @param from     first day, inclusive; defaults to 30 days before {@code to}
     * @param to       last day, inclusive; defaults to today
     * @return rates, oldest first
     */
    @GetMapping("/fx-rates")
    @Operation(summary = "List FX rates against INR")
    public List<FxRateResponse> fxRates(
            @RequestParam @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code") String currency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return fx.list(currency, from, to);
    }

    /**
     * Sets a day's FX rate.
     *
     * @param request the rate
     * @return the stored rate
     */
    @PostMapping("/fx-rates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Set an FX rate (admin only)",
        description = "Rates are shared by all users. A second write for the same currency and day replaces "
            + "the first. Non-admins receive 403.")
    public FxRateResponse recordFxRate(@Valid @RequestBody RecordFxRateRequest request) {
        return fx.record(request, RecordSource.MANUAL);
    }

    /**
     * Converts an amount between currencies.
     *
     * @param amount amount to convert, a decimal string
     * @param from   source currency
     * @param to     target currency
     * @param date   day to value on; defaults to today
     * @return the converted amount
     */
    @GetMapping("/fx-rates/convert")
    @Operation(summary = "Convert an amount between currencies",
        description = "Converts through INR using the latest rate on or before the day. 422 "
            + "FX_RATE_UNAVAILABLE when a currency has no rate yet.")
    public ConversionResponse convert(
            @RequestParam @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$",
                message = "must be a non-negative decimal string with at most 2 decimals") String amount,
            @RequestParam @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code") String from,
            @RequestParam @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 code") String to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return fx.convert(Money.of(new BigDecimal(amount), from), to, date);
    }

    /**
     * Pulls the latest prices and rates from the price feed.
     *
     * @return how many of each were stored
     */
    @PostMapping("/price-feed/sync")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Pull prices and FX rates from the price feed (admin only)",
        description = "Runs the configured feed once and stores what it returns. The bundled feed is a stub "
            + "that returns nothing. Non-admins receive 403.")
    public PriceFeedSyncResponse syncPriceFeed() {
        return feedSync.sync();
    }
}
