package com.rohit.nyvra.expense;

import java.time.YearMonth;

import com.rohit.nyvra.expense.dto.SpendingHabitsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Spending summaries under {@code /api/v1/spending}. Thin: the maths lives in {@link SpendingService}. */
@RestController
@RequestMapping("/api/v1/spending")
@Tag(name = "Spending")
@SecurityRequirement(name = "keycloak")
public class SpendingController {

    /** Use-case layer every endpoint delegates to. */
    private final SpendingService service;

    /**
     * Creates the controller.
     *
     * @param service the spending service
     */
    public SpendingController(SpendingService service) {
        this.service = service;
    }

    /**
     * One month's spending breakdown.
     *
     * @param month {@code yyyy-MM}; defaults to the current month in Asia/Kolkata; a malformed value is a 400
     * @return totals, essential/discretionary/debt shares and the per-category amounts and shares
     */
    @GetMapping("/habits")
    @Operation(summary = "Monthly spending breakdown",
        description = "Excludes SAVINGS_TRANSFER and excluded-from-habits expenses from spending totals; savings "
            + "transfers are reported separately.")
    public SpendingHabitsResponse habits(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return service.habits(month);
    }
}
