package com.rohit.nyvra.goals;

import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.goals.dto.CreateGoalContributionRequest;
import com.rohit.nyvra.goals.dto.CreateGoalRequest;
import com.rohit.nyvra.goals.dto.GoalContributionResponse;
import com.rohit.nyvra.goals.dto.GoalResponse;
import com.rohit.nyvra.goals.dto.UpdateGoalRequest;
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
 * REST endpoints for goals and their contributions under {@code /api/v1/goals}, described in
 * {@code openapi/fragments/goals.yaml}. Thin by design: binding and validation happen here, every rule lives in
 * {@link GoalService}.
 */
@RestController
@RequestMapping("/api/v1/goals")
@Tag(name = "Goals")
@SecurityRequirement(name = "keycloak")
public class GoalController {

    /** Use-case layer every endpoint delegates to. */
    private final GoalService service;

    /**
     * Creates the controller.
     *
     * @param service the goal use-case service
     */
    public GoalController(GoalService service) {
        this.service = service;
    }

    /**
     * Lists the caller's goals with progress, paged, optionally filtered by status.
     *
     * @param status  only goals in these states; omitted or empty means all
     * @param page    zero-based page index, at least 0 (default 0)
     * @param size    page size between 1 and 100 (default 20)
     * @param request the raw request, used to read the repeatable {@code sort} parameter
     * @return one page of goals; an unknown sort field is rejected with 400
     */
    @GetMapping
    @Operation(summary = "List goals",
        parameters = @Parameter(name = "sort", in = ParameterIn.QUERY, description =
            "`field,asc|desc`, repeatable. Fields: name, targetDate, createdAt. Default `targetDate,asc`.",
            array = @ArraySchema(schema = @Schema(type = "string"))))
    public PageResponse<GoalResponse> listGoals(
            @RequestParam(required = false) List<GoalStatus> status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return service.listGoals(status, page, size, sortOf(request));
    }

    /**
     * Creates a goal for the caller.
     *
     * @param request the new goal
     * @return 201 with the created goal and a {@code Location} header pointing at it
     */
    @PostMapping
    @Operation(summary = "Create a goal")
    public ResponseEntity<GoalResponse> createGoal(@Valid @RequestBody CreateGoalRequest request) {
        GoalResponse created = service.createGoal(request);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Returns one goal with its progress.
     *
     * @param goalId the goal id
     * @return the goal; another user's goal is reported as 404
     */
    @GetMapping("/{goalId}")
    @Operation(summary = "Get one goal")
    public GoalResponse getGoal(@PathVariable UUID goalId) {
        return service.getGoal(goalId);
    }

    /**
     * Applies a partial update to a goal.
     *
     * @param goalId  the goal id
     * @param request the fields to change
     * @return the updated goal; another user's goal is reported as 404
     */
    @PatchMapping("/{goalId}")
    @Operation(summary = "Update a goal")
    public GoalResponse updateGoal(@PathVariable UUID goalId, @Valid @RequestBody UpdateGoalRequest request) {
        return service.updateGoal(goalId, request);
    }

    /**
     * Deletes a goal and its contributions.
     *
     * @param goalId the goal id; another user's goal is reported as 404
     */
    @DeleteMapping("/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a goal")
    public void deleteGoal(@PathVariable UUID goalId) {
        service.deleteGoal(goalId);
    }

    /**
     * Lists the contributions to a goal, paged.
     *
     * @param goalId  the goal id
     * @param page    zero-based page index, at least 0 (default 0)
     * @param size    page size between 1 and 100 (default 20)
     * @param request the raw request, used to read the repeatable {@code sort} parameter
     * @return one page of contributions; another user's goal is reported as 404
     */
    @GetMapping("/{goalId}/contributions")
    @Operation(summary = "List contributions to a goal",
        parameters = @Parameter(name = "sort", in = ParameterIn.QUERY, description =
            "`field,asc|desc`, repeatable. Fields: contributedOn, createdAt. Default `contributedOn,desc`.",
            array = @ArraySchema(schema = @Schema(type = "string"))))
    public PageResponse<GoalContributionResponse> listContributions(
            @PathVariable UUID goalId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return service.listContributions(goalId, page, size, sortOf(request));
    }

    /**
     * Records an amount set aside for a goal.
     *
     * @param goalId  the goal id
     * @param request the new contribution
     * @return 201 with the contribution; 409 {@code GOAL_ARCHIVED} for an archived goal
     */
    @PostMapping("/{goalId}/contributions")
    @Operation(summary = "Add a contribution to a goal")
    public ResponseEntity<GoalContributionResponse> addContribution(
            @PathVariable UUID goalId, @Valid @RequestBody CreateGoalContributionRequest request) {
        GoalContributionResponse created = service.addContribution(goalId, request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(created.id()).toUri()).body(created);
    }

    /**
     * Deletes a contribution.
     *
     * @param goalId         the goal id
     * @param contributionId the contribution id; 404 if the goal or contribution is not the caller's
     */
    @DeleteMapping("/{goalId}/contributions/{contributionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a contribution")
    public void deleteContribution(@PathVariable UUID goalId, @PathVariable UUID contributionId) {
        service.deleteContribution(goalId, contributionId);
    }

    /**
     * Reads the repeatable {@code sort} query parameter.
     *
     * @param request the raw request
     * @return the values, or {@code null} when none were sent
     */
    private static List<String> sortOf(HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return sort == null ? null : List.of(sort);
    }
}
