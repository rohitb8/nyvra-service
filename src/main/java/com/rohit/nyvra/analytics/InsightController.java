package com.rohit.nyvra.analytics;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.analytics.dto.InsightResponse;
import com.rohit.nyvra.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for insights under {@code /api/v1/insights}, as described in
 * {@code openapi/nyvra-api-v1.yaml}. Thin by design: every rule lives in {@link InsightService}.
 */
@RestController
@RequestMapping("/api/v1/insights")
@Tag(name = "Insights")
@SecurityRequirement(name = "keycloak")
public class InsightController {

    /** Use-case layer behind both endpoints. */
    private final InsightService service;

    /**
     * Creates the controller.
     *
     * @param service the insight use-case service
     */
    public InsightController(InsightService service) {
        this.service = service;
    }

    /**
     * Lists the caller's insights, most serious first.
     *
     * @param status   lifecycle state to list (default {@code ACTIVE})
     * @param severity only these severities; omitted or empty means all
     * @param page     zero-based page index, at least 0 (default 0)
     * @param size     page size between 1 and 100 (default 20)
     * @return one page of insights
     */
    @GetMapping
    @Operation(summary = "List insights")
    public PageResponse<InsightResponse> list(
            @RequestParam(defaultValue = "ACTIVE") InsightStatus status,
            @RequestParam(required = false) List<InsightSeverity> severity,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, severity, page, size);
    }

    /**
     * Dismisses one of the caller's insights.
     *
     * @param insightId the insight id
     * @return the dismissed insight; 404 if unknown, 409 {@code INSIGHT_SUPERSEDED} if already replaced
     */
    @PostMapping("/{insightId}/dismiss")
    @Operation(summary = "Dismiss an insight")
    public InsightResponse dismiss(@PathVariable UUID insightId) {
        return service.dismiss(insightId);
    }
}
