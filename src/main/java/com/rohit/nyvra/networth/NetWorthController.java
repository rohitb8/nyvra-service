package com.rohit.nyvra.networth;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rohit.nyvra.networth.dto.NetWorthHistoryResponse;
import com.rohit.nyvra.networth.dto.NetWorthResponse;

/** Net-worth endpoints per {@code openapi/nyvra-api-v1.yaml}. Thin: all rules live in {@link NetWorthService}. */
@RestController
@RequestMapping("/api/v1/net-worth")
@Tag(name = "Net worth")
@SecurityRequirement(name = "keycloak")
public class NetWorthController {

    /** The use cases behind the endpoints. */
    private final NetWorthService service;

    /**
     * Creates the controller.
     *
     * @param service the net-worth service
     */
    public NetWorthController(NetWorthService service) {
        this.service = service;
    }

    /**
     * Returns the live net worth.
     *
     * @return the figure with its breakdown
     */
    @GetMapping
    @Operation(summary = "Current net worth",
        description = "Computed on demand from accounts, manual entries and the portfolio; nothing is stored.")
    public NetWorthResponse get() {
        return service.current();
    }

    /**
     * Stores today's snapshot.
     *
     * @return the stored figure
     */
    @PostMapping("/snapshots")
    @Operation(summary = "Record today's snapshot",
        description = "Values net worth now and stores it as today's point in the trend, replacing an earlier "
            + "one from the same day. Safe to call repeatedly.")
    public NetWorthResponse snapshot() {
        return service.recordSnapshot();
    }

    /**
     * Returns the net-worth trend.
     *
     * @param from        first day, default 180 days before {@code to}
     * @param to          last day, default today
     * @param granularity bucket size, default {@code WEEK}
     * @return the trend
     */
    @GetMapping("/history")
    @Operation(summary = "Net worth trend",
        description = "One point per bucket: the last snapshot in it. Buckets without a snapshot are omitted.")
    public NetWorthHistoryResponse history(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Granularity granularity) {
        return service.history(from, to, granularity);
    }
}
