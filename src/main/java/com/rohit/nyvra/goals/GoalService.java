package com.rohit.nyvra.goals;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.ConflictException;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.goals.dto.CreateGoalContributionRequest;
import com.rohit.nyvra.goals.dto.CreateGoalRequest;
import com.rohit.nyvra.goals.dto.GoalContributionResponse;
import com.rohit.nyvra.goals.dto.GoalResponse;
import com.rohit.nyvra.goals.dto.UpdateGoalRequest;
import com.rohit.nyvra.user.CurrentUserService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goal use cases for the signed-in user, plus the {@link GoalProgressSource} other modules read. Every lookup is
 * scoped to the caller, so another user's goal is indistinguishable from a missing one (404, never 403).
 *
 * <p>Business rules enforced here: INR only ({@code UNSUPPORTED_CURRENCY}), target date after the start date
 * ({@code GOAL_DATES_INVALID}) and, on creation, after today ({@code GOAL_TARGET_DATE_IN_PAST}), contributions not
 * dated in the future ({@code CONTRIBUTION_DATE_IN_FUTURE}), and no contributions to an archived goal
 * ({@code GOAL_ARCHIVED}, 409). "Today" is the Asia/Kolkata date.
 */
@Service
public class GoalService implements GoalProgressSource {

    /** The only currency supported in v1. */
    private static final String SUPPORTED_CURRENCY = Money.INR;
    /** Display time zone that decides what "today" means for accounting dates. */
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Kolkata");
    /** Weight used when a request does not give one. */
    private static final int DEFAULT_WEIGHT = 1;

    /** Goal properties a client may sort by. */
    private static final Set<String> GOAL_SORTABLE = Set.of("name", "targetDate", "createdAt");
    /** Contribution properties a client may sort by. */
    private static final Set<String> CONTRIBUTION_SORTABLE = Set.of("contributedOn", "createdAt");

    /** Goal persistence. */
    private final GoalRepository goals;
    /** Contribution persistence. */
    private final GoalContributionRepository contributions;
    /** Resolves the signed-in user that every query is scoped to. */
    private final CurrentUserService currentUser;
    /** Source of "today", in the user's time zone. */
    private final Clock clock = Clock.system(USER_ZONE);

    /**
     * Creates the service.
     *
     * @param goals         the goal repository
     * @param contributions the contribution repository
     * @param currentUser   the signed-in user resolver
     */
    public GoalService(GoalRepository goals, GoalContributionRepository contributions,
                       CurrentUserService currentUser) {
        this.goals = goals;
        this.contributions = contributions;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ goals

    /**
     * Lists the caller's goals with progress as of today.
     *
     * @param statuses only goals in these states; {@code null} or empty means all
     * @param page     zero-based page index
     * @param size     page size
     * @param sort     {@code field,asc|desc} entries; {@code null} or empty sorts by target date ascending
     * @return one page of goals
     * @throws BadRequestException if a sort field or direction is invalid
     */
    @Transactional(readOnly = true)
    public PageResponse<GoalResponse> listGoals(Collection<GoalStatus> statuses, int page, int size,
                                                List<String> sort) {
        UUID userId = currentUser.currentUser().getId();
        Specification<Goal> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("userId"), userId));
            if (statuses != null && !statuses.isEmpty()) {
                where.add(root.get("status").in(statuses));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(page, size,
            parseSort(sort, GOAL_SORTABLE, Sort.Order.asc("targetDate"), "goals"));
        var result = goals.findAll(spec, pageable);

        LocalDate today = today();
        Map<UUID, Money> totals = totalsFor(result.getContent());
        return PageResponse.of(result, g -> GoalResponse.from(g,
            GoalProgressCalculator.calculate(g, totals.getOrDefault(g.getId(), Money.zero(g.getCurrency())), today)));
    }

    /**
     * Returns one of the caller's goals with progress as of today.
     *
     * @param id the goal id
     * @return the goal
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional(readOnly = true)
    public GoalResponse getGoal(UUID id) {
        return toResponse(ownedGoal(id));
    }

    /**
     * Creates an active goal for the caller.
     *
     * @param request the new goal
     * @return the created goal
     * @throws BadRequestException if the target amount is not positive
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY}, {@code GOAL_DATES_INVALID} or
     *         {@code GOAL_TARGET_DATE_IN_PAST}
     */
    @Transactional
    public GoalResponse createGoal(CreateGoalRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Money target = positiveInr(request.targetAmount(), "targetAmount");
        LocalDate today = today();
        LocalDate start = request.startDate() != null ? request.startDate() : today;
        if (!request.targetDate().isAfter(today)) {
            throw new UnprocessableEntityException(
                "GOAL_TARGET_DATE_IN_PAST", "The target date must be after today");
        }
        requireDatesOrdered(start, request.targetDate());
        int weight = request.weight() != null ? request.weight() : DEFAULT_WEIGHT;
        Goal goal = goals.save(new Goal(userId, request.name().strip(), request.type(), target, start,
            request.targetDate(), weight));
        return toResponse(goal);
    }

    /**
     * Applies a partial update; omitted fields are unchanged and the merged result is validated as a whole.
     *
     * @param id      the goal id
     * @param request the fields to change
     * @return the updated goal
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     * @throws BadRequestException if the new target amount is not positive
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY} or {@code GOAL_DATES_INVALID}
     */
    @Transactional
    public GoalResponse updateGoal(UUID id, UpdateGoalRequest request) {
        Goal goal = ownedGoal(id);
        Money target = request.targetAmount() != null
            ? positiveInr(request.targetAmount(), "targetAmount") : goal.getTargetAmount();
        LocalDate targetDate = request.targetDate() != null ? request.targetDate() : goal.getTargetDate();
        requireDatesOrdered(goal.getStartDate(), targetDate);
        goal.revise(
            request.name() != null ? request.name().strip() : goal.getName(),
            request.type() != null ? request.type() : goal.getType(),
            target, targetDate,
            request.weight() != null ? request.weight() : goal.getWeight());
        if (request.status() != null) {
            goal.changeStatus(request.status());
        }
        return toResponse(goal);
    }

    /**
     * Deletes a goal together with its contributions.
     *
     * @param id the goal id
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional
    public void deleteGoal(UUID id) {
        goals.delete(ownedGoal(id));
    }

    // ------------------------------------------------------------------ contributions

    /**
     * Lists the contributions to one of the caller's goals.
     *
     * @param goalId the goal id
     * @param page   zero-based page index
     * @param size   page size
     * @param sort   {@code field,asc|desc} entries; {@code null} or empty sorts by date, newest first
     * @return one page of contributions
     * @throws ResourceNotFoundException if the goal does not exist or belongs to someone else
     * @throws BadRequestException if a sort field or direction is invalid
     */
    @Transactional(readOnly = true)
    public PageResponse<GoalContributionResponse> listContributions(UUID goalId, int page, int size,
                                                                    List<String> sort) {
        Goal goal = ownedGoal(goalId);
        PageRequest pageable = PageRequest.of(page, size,
            parseSort(sort, CONTRIBUTION_SORTABLE, Sort.Order.desc("contributedOn"), "contributions"));
        return PageResponse.of(contributions.findByGoalId(goal.getId(), pageable), GoalContributionResponse::from);
    }

    /**
     * Records an amount set aside for one of the caller's goals.
     *
     * @param goalId  the goal id
     * @param request the new contribution
     * @return the created contribution
     * @throws ResourceNotFoundException if the goal does not exist or belongs to someone else
     * @throws ConflictException {@code GOAL_ARCHIVED} if the goal is archived
     * @throws BadRequestException if the amount is not positive
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY} or {@code CONTRIBUTION_DATE_IN_FUTURE}
     */
    @Transactional
    public GoalContributionResponse addContribution(UUID goalId, CreateGoalContributionRequest request) {
        Goal goal = ownedGoal(goalId);
        if (goal.getStatus() == GoalStatus.ARCHIVED) {
            throw new ConflictException("GOAL_ARCHIVED", "Archived goals don't accept contributions");
        }
        Money amount = positiveInr(request.amount(), "amount");
        LocalDate today = today();
        LocalDate on = request.contributedOn() != null ? request.contributedOn() : today;
        if (on.isAfter(today)) {
            throw new UnprocessableEntityException(
                "CONTRIBUTION_DATE_IN_FUTURE", "A contribution can't be dated in the future");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        return GoalContributionResponse.from(
            contributions.save(new GoalContribution(goal.getId(), goal.getUserId(), amount, on, note)));
    }

    /**
     * Deletes a contribution, for example to correct a mistake.
     *
     * @param goalId         the goal id
     * @param contributionId the contribution id
     * @throws ResourceNotFoundException if the goal or the contribution does not exist for the caller
     */
    @Transactional
    public void deleteContribution(UUID goalId, UUID contributionId) {
        Goal goal = ownedGoal(goalId);
        GoalContribution contribution = contributions.findByIdAndGoalId(contributionId, goal.getId())
            .orElseThrow(() -> ResourceNotFoundException.of("Goal contribution", contributionId));
        contributions.delete(contribution);
    }

    // ------------------------------------------------------------------ progress for other modules

    /** {@inheritDoc} */
    @Override
    @Transactional(readOnly = true)
    public List<GoalProgressSnapshot> activeGoalProgress(UUID userId, LocalDate asOf) {
        List<Goal> active = goals.findByUserIdAndStatus(userId, GoalStatus.ACTIVE);
        Map<UUID, Money> totals = totalsFor(active);
        return active.stream()
            .map(g -> new GoalProgressSnapshot(g.getId(), g.getWeight(), GoalProgressCalculator
                .calculate(g, totals.getOrDefault(g.getId(), Money.zero(g.getCurrency())), asOf).progressRatio()))
            .toList();
    }

    // ------------------------------------------------------------------ helpers

    /** @return today's date in the user's display time zone */
    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Loads a goal owned by the caller.
     *
     * @param id the goal id
     * @return the goal
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    private Goal ownedGoal(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return goals.findByIdAndUserId(id, userId).orElseThrow(() -> ResourceNotFoundException.of("Goal", id));
    }

    /**
     * Maps one goal to its response with today's progress.
     *
     * @param goal the goal
     * @return the response
     */
    private GoalResponse toResponse(Goal goal) {
        Money allocated = totalsFor(List.of(goal)).getOrDefault(goal.getId(), Money.zero(goal.getCurrency()));
        return GoalResponse.from(goal, GoalProgressCalculator.calculate(goal, allocated, today()));
    }

    /**
     * Sums contributions for several goals with one query.
     *
     * @param forGoals the goals
     * @return the allocated amount per goal id; goals without contributions are absent
     */
    private Map<UUID, Money> totalsFor(Collection<Goal> forGoals) {
        if (forGoals.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> currencies = forGoals.stream().collect(Collectors.toMap(Goal::getId, Goal::getCurrency));
        return contributions.totalsByGoalIds(currencies.keySet()).stream().collect(Collectors.toMap(
            GoalContributionRepository.GoalTotal::getGoalId,
            t -> Money.of(t.getTotal(), currencies.get(t.getGoalId())), (a, b) -> a));
    }

    /**
     * Enforces that the target date is after the start date.
     *
     * @param start  the start date
     * @param target the target date
     * @throws UnprocessableEntityException {@code GOAL_DATES_INVALID} otherwise
     */
    private static void requireDatesOrdered(LocalDate start, LocalDate target) {
        if (!target.isAfter(start)) {
            throw new UnprocessableEntityException("GOAL_DATES_INVALID", "The target date must be after the start date");
        }
    }

    /**
     * Converts an amount, checking that it is greater than zero and in INR.
     *
     * @param dto   the amount
     * @param field the request field name, used in the error message
     * @return the amount
     * @throws BadRequestException if it is zero or negative
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY} for anything but INR
     */
    private static Money positiveInr(MoneyDto dto, String field) {
        Money money = dto.toMoney();
        if (money.amount().signum() <= 0) {
            throw new BadRequestException(field + " must be greater than zero");
        }
        if (!SUPPORTED_CURRENCY.equals(money.currency())) {
            throw new UnprocessableEntityException("UNSUPPORTED_CURRENCY", "Only INR is supported");
        }
        return money;
    }

    /**
     * Parses {@code field,asc|desc} entries into a {@link Sort}; unknown fields are a 400. The id is always
     * appended as a final tie-breaker so paging is stable.
     *
     * @param sort     the raw values, may be {@code null} or empty
     * @param sortable the fields that may be sorted on
     * @param fallback the order used when none is requested
     * @param what     plural noun used in error messages
     * @return the sort to apply
     * @throws BadRequestException for an unknown field, extra comma parts or a direction other than asc/desc
     */
    private static Sort parseSort(List<String> sort, Set<String> sortable, Sort.Order fallback, String what) {
        if (sort == null || sort.isEmpty()) {
            return Sort.by(fallback, Sort.Order.asc("id"));
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String entry : sort) {
            String[] parts = entry.split(",");
            String field = parts[0].trim();
            if (!sortable.contains(field) || parts.length > 2) {
                throw new BadRequestException("Can't sort " + what + " by '" + entry + "'");
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
