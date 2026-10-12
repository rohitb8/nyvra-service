package com.rohit.nyvra.expense;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.expense.dto.CategoryRef;
import com.rohit.nyvra.expense.dto.CategorisationRuleRequest;
import com.rohit.nyvra.expense.dto.CategorisationRuleResponse;
import com.rohit.nyvra.expense.dto.UpdateCategorisationRuleRequest;
import com.rohit.nyvra.user.CurrentUserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categorisation-rule use cases for the signed-in user. Only the caller's own rules are visible: system rules are
 * never exposed, and another user's rule is indistinguishable from a missing one (404). A regex matcher must
 * compile and an MCC matcher must be exactly four digits.
 */
@Service
public class CategorisationRuleService {

    /** Priority given to a rule created without one. */
    static final int DEFAULT_PRIORITY = 100;

    /** Code for a matcher the user already has a rule for. */
    static final String RULE_EXISTS = "RULE_EXISTS";

    /** Fields a rule list can be sorted by. */
    private static final Set<String> SORTABLE = Set.of("priority", "matcherType");

    /** A merchant category code is exactly four digits. */
    private static final Pattern MCC = Pattern.compile("^\\d{4}$");

    /** Rule persistence. */
    private final CategorisationRuleRepository rules;

    /** Category persistence. */
    private final CategoryRepository categories;

    /** Resolves the caller. */
    private final CurrentUserService currentUser;

    /** Publishes {@link RuleCreatedEvent} for the optional back-fill. */
    private final ApplicationEventPublisher events;

    /**
     * Creates the service.
     *
     * @param rules       rule persistence
     * @param categories  category persistence
     * @param currentUser resolves the caller
     * @param events      publishes the back-fill event
     */
    public CategorisationRuleService(CategorisationRuleRepository rules, CategoryRepository categories,
                                     CurrentUserService currentUser, ApplicationEventPublisher events) {
        this.rules = rules;
        this.categories = categories;
        this.currentUser = currentUser;
        this.events = events;
    }

    /**
     * Lists the caller's rules, highest priority first unless sorted otherwise.
     *
     * @param page zero-based page index
     * @param size page size
     * @param sort {@code field,asc|desc} entries; fields {@code priority} and {@code matcherType}
     * @return one page of rules
     * @throws BadRequestException if a sort field or direction is invalid
     */
    @Transactional(readOnly = true)
    public PageResponse<CategorisationRuleResponse> list(int page, int size, List<String> sort) {
        UUID userId = currentUser.currentUser().getId();
        Page<CategorisationRule> result = rules.findByUserId(userId, PageRequest.of(page, size, parseSort(sort)));
        Map<UUID, Category> visible = visibleById(userId);
        return PageResponse.of(result, r -> toResponse(r, visible));
    }

    /**
     * Returns one of the caller's rules.
     *
     * @param id the rule id
     * @return the rule
     * @throws ResourceNotFoundException if it does not exist or is not the caller's
     */
    @Transactional(readOnly = true)
    public CategorisationRuleResponse get(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return toResponse(owned(userId, id), visibleById(userId));
    }

    /**
     * Creates a rule. With {@code applyToExisting} the rule is afterwards applied, in the background, to existing
     * expenses whose category the user never chose (see {@link RuleBackfill}).
     *
     * @param request         the new rule
     * @param applyToExisting whether to re-categorise matching history
     * @return the created rule
     * @throws BadRequestException if the matcher value is not a valid regex or MCC
     * @throws ResourceNotFoundException if the category is not visible to the caller
     * @throws ConflictException {@code RULE_EXISTS} if the caller already has a rule with this matcher
     */
    @Transactional
    public CategorisationRuleResponse create(CategorisationRuleRequest request, boolean applyToExisting) {
        UUID userId = currentUser.currentUser().getId();
        Map<UUID, Category> visible = visibleById(userId);
        requireCategory(visible, request.categoryId());
        String value = request.matcherValue().strip();
        validateMatcher(request.matcherType(), value);
        if (rules.existsByUserIdAndMatcherTypeAndMatcherValue(userId, request.matcherType(), value)) {
            throw new ConflictException(RULE_EXISTS, "You already have a rule for this matcher");
        }
        CategorisationRule rule = rules.save(new CategorisationRule(userId, request.matcherType(), value,
            request.categoryId(), request.necessity(),
            request.priority() == null ? DEFAULT_PRIORITY : request.priority()));
        if (applyToExisting) {
            events.publishEvent(new RuleCreatedEvent(userId, rule.getId()));
        }
        return toResponse(rule, visible);
    }

    /**
     * Updates a rule; omitted fields are unchanged. The merged matcher is validated as a whole.
     *
     * @param id      the rule id
     * @param request the fields to change
     * @return the updated rule
     * @throws ResourceNotFoundException if the rule or the new category is not visible to the caller
     * @throws BadRequestException if the resulting matcher value is not a valid regex or MCC
     * @throws ConflictException {@code RULE_EXISTS} if the change collides with another of the caller's rules
     */
    @Transactional
    public CategorisationRuleResponse update(UUID id, UpdateCategorisationRuleRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Map<UUID, Category> visible = visibleById(userId);
        CategorisationRule rule = owned(userId, id);

        MatcherType type = request.matcherType() != null ? request.matcherType() : rule.getMatcherType();
        String value = request.matcherValue() != null ? request.matcherValue().strip() : rule.getMatcherValue();
        if (!type.equals(rule.getMatcherType()) || !value.equals(rule.getMatcherValue())) {
            validateMatcher(type, value);
            if (rules.existsByUserIdAndMatcherTypeAndMatcherValue(userId, type, value)) {
                throw new ConflictException(RULE_EXISTS, "You already have a rule for this matcher");
            }
            rule.rematch(type, value);
        }
        if (request.categoryId() != null || request.necessity() != null) {
            UUID categoryId = request.categoryId() != null ? request.categoryId() : rule.getCategoryId();
            requireCategory(visible, categoryId);
            rule.retarget(categoryId, request.necessity() != null ? request.necessity() : rule.getNecessity());
        }
        if (request.priority() != null) {
            rule.changePriority(request.priority());
        }
        return toResponse(rules.saveAndFlush(rule), visible);
    }

    /**
     * Deletes a rule. Expenses it already categorised keep their category.
     *
     * @param id the rule id
     * @throws ResourceNotFoundException if it does not exist or is not the caller's
     */
    @Transactional
    public void delete(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        rules.delete(owned(userId, id));
    }

    private CategorisationRule owned(UUID userId, UUID id) {
        return rules.findByIdAndUserId(id, userId).orElseThrow(() -> ResourceNotFoundException.of("Categorisation rule", id));
    }

    private Map<UUID, Category> visibleById(UUID userId) {
        return categories.findVisibleTo(userId).stream().collect(Collectors.toMap(Category::getId, Function.identity()));
    }

    private static void requireCategory(Map<UUID, Category> visible, UUID categoryId) {
        if (!visible.containsKey(categoryId)) {
            throw ResourceNotFoundException.of("Category", categoryId);
        }
    }

    /** A regex matcher must compile; an MCC must be four digits. */
    private static void validateMatcher(MatcherType type, String value) {
        if (type == MatcherType.MCC) {
            if (!MCC.matcher(value).matches()) {
                throw new BadRequestException("An MCC matcherValue must be a 4-digit code");
            }
        } else {
            SafeRegex.compile(value);
        }
    }

    private static CategorisationRuleResponse toResponse(CategorisationRule rule, Map<UUID, Category> visible) {
        return CategorisationRuleResponse.from(rule, CategoryRef.from(visible.get(rule.getCategoryId())));
    }

    /** Parses {@code field,asc|desc} entries; unknown fields and directions are rejected. Defaults to priority desc. */
    private static Sort parseSort(List<String> sort) {
        if (sort == null || sort.isEmpty()) {
            return Sort.by(Sort.Order.desc("priority"), Sort.Order.asc("id"));
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String entry : sort) {
            String[] parts = entry.split(",");
            String field = parts[0].trim();
            if (!SORTABLE.contains(field) || parts.length > 2) {
                throw new BadRequestException("Can't sort categorisation rules by '" + entry + "'");
            }
            boolean desc = parts.length == 2 && parts[1].trim().equalsIgnoreCase("desc");
            if (parts.length == 2 && !desc && !parts[1].trim().equalsIgnoreCase("asc")) {
                throw new BadRequestException("Sort direction must be asc or desc: '" + entry + "'");
            }
            orders.add(desc ? Sort.Order.desc(field) : Sort.Order.asc(field));
        }
        orders.add(Sort.Order.asc("id"));
        return Sort.by(orders);
    }
}
