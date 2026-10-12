package com.rohit.nyvra.expense;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.expense.dto.CategorisationRuleRequest;
import com.rohit.nyvra.expense.dto.CategorisationRuleResponse;
import com.rohit.nyvra.expense.dto.UpdateCategorisationRuleRequest;
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
 * Categorisation-rule endpoints under {@code /api/v1/categorisation-rules} per {@code openapi/nyvra-api-v1.yaml}.
 * Thin: every rule lives in {@link CategorisationRuleService}.
 */
@RestController
@RequestMapping("/api/v1/categorisation-rules")
@Tag(name = "Categories")
@SecurityRequirement(name = "keycloak")
public class CategorisationRuleController {

    /** Use-case layer every endpoint delegates to. */
    private final CategorisationRuleService service;

    /**
     * Creates the controller.
     *
     * @param service the rule use-case service
     */
    public CategorisationRuleController(CategorisationRuleService service) {
        this.service = service;
    }

    /**
     * Lists the caller's rules; system rules are not exposed.
     *
     * @param page    zero-based page index, at least 0 (default 0)
     * @param size    page size between 1 and 100 (default 20)
     * @param request the raw request, used to read the repeatable {@code sort} parameter
     * @return one page of rules; an unknown sort field is rejected with 400
     */
    @GetMapping
    @Operation(summary = "List the caller's categorisation rules",
        parameters = @Parameter(name = "sort", in = ParameterIn.QUERY, description =
            "`field,asc|desc`, repeatable. Fields: priority, matcherType. Default `priority,desc`.",
            array = @ArraySchema(schema = @Schema(type = "string"))))
    public PageResponse<CategorisationRuleResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return service.list(page, size, sort == null ? List.of() : Arrays.asList(sort));
    }

    /**
     * Creates a rule.
     *
     * @param applyToExisting also re-categorise existing expenses the user never categorised by hand; runs in the
     *                        background after the response
     * @param request         the new rule
     * @return 201 with the rule and its {@code Location}
     */
    @PostMapping
    @Operation(summary = "Create a categorisation rule",
        description = "With `applyToExisting=true`, matching expenses whose category was not chosen by hand are "
            + "re-categorised in the background. Only MERCHANT_REGEX rules change history.")
    public ResponseEntity<CategorisationRuleResponse> create(
            @RequestParam(defaultValue = "false") boolean applyToExisting,
            @Valid @RequestBody CategorisationRuleRequest request) {
        CategorisationRuleResponse created = service.create(request, applyToExisting);
        return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequest().replaceQuery(null).path("/{id}")
                .buildAndExpand(created.id()).toUri())
            .body(created);
    }

    /**
     * Returns one rule.
     *
     * @param ruleId the rule id
     * @return the rule
     */
    @GetMapping("/{ruleId}")
    @Operation(summary = "Get one rule")
    public CategorisationRuleResponse get(@PathVariable UUID ruleId) {
        return service.get(ruleId);
    }

    /**
     * Updates a rule; omitted fields are unchanged.
     *
     * @param ruleId  the rule id
     * @param request the fields to change
     * @return the updated rule
     */
    @PatchMapping("/{ruleId}")
    @Operation(summary = "Update a rule")
    public CategorisationRuleResponse update(@PathVariable UUID ruleId,
                                             @Valid @RequestBody UpdateCategorisationRuleRequest request) {
        return service.update(ruleId, request);
    }

    /**
     * Deletes a rule; expenses it already categorised keep their category.
     *
     * @param ruleId the rule id
     */
    @DeleteMapping("/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a rule", description = "Already-categorised expenses keep their category.")
    public void delete(@PathVariable UUID ruleId) {
        service.delete(ruleId);
    }
}
