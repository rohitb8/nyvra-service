package com.rohit.nyvra.dashboard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rohit.nyvra.dashboard.dto.DashboardSummaryResponse;

/** Dashboard endpoint. Thin: assembly lives in {@link DashboardService}. */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
@SecurityRequirement(name = "keycloak")
public class DashboardController {

    /** The use case behind the endpoint. */
    private final DashboardService service;

    /**
     * Creates the controller.
     *
     * @param service the dashboard service
     */
    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /**
     * Returns the home-screen summary.
     *
     * @return net worth, portfolio, health score, goals, income and spending headlines
     */
    @GetMapping("/summary")
    @Operation(summary = "Dashboard summary",
        description = "One call for the home screen. A section that fails to load is omitted and named in "
            + "`unavailable`; the rest is still returned.")
    public DashboardSummaryResponse summary() {
        return service.summary();
    }
}
