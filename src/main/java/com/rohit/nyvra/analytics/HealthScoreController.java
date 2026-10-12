package com.rohit.nyvra.analytics;

import java.time.LocalDate;

import com.rohit.nyvra.analytics.dto.HealthScoreHistoryResponse;
import com.rohit.nyvra.analytics.dto.HealthScoreResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the Financial Health Score under {@code /api/v1/health-score}, as described in
 * {@code openapi/nyvra-api-v1.yaml}. Thin by design: every rule lives in {@link HealthScoreService}.
 */
@RestController
@RequestMapping("/api/v1/health-score")
@Tag(name = "Health score")
@SecurityRequirement(name = "keycloak")
public class HealthScoreController {

    /** Use-case layer behind both endpoints. */
    private final HealthScoreService service;

    /**
     * Creates the controller.
     *
     * @param service the health score use-case service
     */
    public HealthScoreController(HealthScoreService service) {
        this.service = service;
    }

    /**
     * Returns the caller's latest score.
     *
     * @return the score; with too little data, an {@code INSUFFICIENT_DATA} body listing what to connect
     */
    @GetMapping
    @Operation(summary = "Latest Financial Health Score")
    public HealthScoreResponse latest() {
        return service.latest();
    }

    /**
     * Returns the caller's score trend.
     *
     * @param from        inclusive start date; default 180 days before {@code to}
     * @param to          inclusive end date; default today (Asia/Kolkata)
     * @param granularity {@code DAY}, {@code WEEK} (default) or {@code MONTH}
     * @return one point per non-empty bucket, oldest first; 400 if {@code from} is after {@code to}
     */
    @GetMapping("/history")
    @Operation(summary = "Health score trend")
    public HealthScoreHistoryResponse history(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "WEEK") HealthScoreService.Granularity granularity) {
        return service.history(from, to, granularity);
    }
}
