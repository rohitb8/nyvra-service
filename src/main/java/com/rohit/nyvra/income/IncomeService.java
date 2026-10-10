package com.rohit.nyvra.income;

import java.time.LocalDate;
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
import com.rohit.nyvra.income.dto.CreateIncomeEntryRequest;
import com.rohit.nyvra.income.dto.CreateIncomeSourceRequest;
import com.rohit.nyvra.income.dto.IncomeEntryResponse;
import com.rohit.nyvra.income.dto.IncomeSourceResponse;
import com.rohit.nyvra.income.dto.UpdateIncomeEntryRequest;
import com.rohit.nyvra.income.dto.UpdateIncomeSourceRequest;
import com.rohit.nyvra.user.CurrentUserService;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Income use cases for the signed-in user. Every lookup is scoped to the caller, so another user's
 * source or entry is indistinguishable from a missing one (404, never 403).
 *
 * <p>Business rules enforced here: expected amount required unless the cadence is {@code IRREGULAR}
 * ({@code EXPECTED_AMOUNT_REQUIRED}), INR only ({@code UNSUPPORTED_CURRENCY}), net not above gross
 * ({@code NET_EXCEEDS_GROSS}), no overlapping periods within a source ({@code INCOME_PERIOD_OVERLAP}), and
 * bank-detected entries are read-only ({@code SOURCE_READ_ONLY}).
 */
@Service
public class IncomeService {

    /** The only currency supported in v1. */
    private static final String SUPPORTED_CURRENCY = Money.INR;

    /** Source properties a client may sort by. */
    private static final Set<String> SOURCE_SORTABLE = Set.of("name", "type", "createdAt");
    /** Entry properties a client may sort by. */
    private static final Set<String> ENTRY_SORTABLE = Set.of("receivedOn", "periodStart", "netAmount");

    /** Source persistence. */
    private final IncomeSourceRepository sources;
    /** Entry persistence. */
    private final IncomeEntryRepository entries;
    /** Payslip persistence, used only to tell whether an entry has a payslip. */
    private final PayslipDocumentRepository payslips;
    /** Resolves the signed-in user that every query is scoped to. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param sources the source repository
     * @param entries the entry repository
     * @param payslips the payslip repository
     * @param currentUser the signed-in user resolver
     */
    public IncomeService(IncomeSourceRepository sources, IncomeEntryRepository entries,
                         PayslipDocumentRepository payslips, CurrentUserService currentUser) {
        this.sources = sources;
        this.entries = entries;
        this.payslips = payslips;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ sources

    /**
     * Lists the caller's sources with optional filters.
     *
     * @param types  only these types; {@code null} or empty means all
     * @param active only active or only inactive sources; {@code null} means both
     * @param page   zero-based page index
     * @param size   page size
     * @param sort   {@code field,asc|desc} entries; {@code null} or empty sorts by name ascending
     * @return one page of sources
     * @throws BadRequestException if a sort field or direction is invalid
     */
    @Transactional(readOnly = true)
    public PageResponse<IncomeSourceResponse> listSources(Collection<IncomeType> types, Boolean active,
                                                          int page, int size, List<String> sort) {
        UUID userId = currentUser.currentUser().getId();
        Specification<IncomeSource> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("userId"), userId));
            if (types != null && !types.isEmpty()) {
                where.add(root.get("type").in(types));
            }
            if (active != null) {
                where.add(cb.equal(root.get("active"), active));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(page, size,
            parseSort(sort, SOURCE_SORTABLE, Sort.Order.asc("name"), "income sources"));
        return PageResponse.of(sources.findAll(spec, pageable), IncomeSourceResponse::from);
    }

    /**
     * Returns one of the caller's sources.
     *
     * @param id the source id
     * @return the source
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional(readOnly = true)
    public IncomeSourceResponse getSource(UUID id) {
        return IncomeSourceResponse.from(ownedSource(id));
    }

    /**
     * Creates a source for the caller. The name is stripped and the currency is always INR.
     *
     * @param request the new source
     * @return the created source
     * @throws BadRequestException if the expected amount is not positive
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY} for a non-INR amount, or
     *         {@code EXPECTED_AMOUNT_REQUIRED} when a non-irregular cadence has no amount
     */
    @Transactional
    public IncomeSourceResponse createSource(CreateIncomeSourceRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Money expected = positiveOrNull(request.expectedAmount(), "expectedAmount");
        if (expected != null) {
            requireSupportedCurrency(expected.currency());
        }
        requireExpectedAmount(request.cadence(), expected);
        return IncomeSourceResponse.from(sources.save(new IncomeSource(
            userId, request.name().strip(), request.type(), SUPPORTED_CURRENCY, request.cadence(), expected)));
    }

    /**
     * Applies a partial update. Omitted fields are unchanged; an explicitly sent {@code expectedAmount: null}
     * clears the amount, which is only valid when the resulting cadence is {@code IRREGULAR}.
     *
     * @param id the source id
     * @param request the fields to change
     * @return the updated source
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     * @throws BadRequestException if the new expected amount is not positive
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY} or {@code EXPECTED_AMOUNT_REQUIRED}
     */
    @Transactional
    public IncomeSourceResponse updateSource(UUID id, UpdateIncomeSourceRequest request) {
        IncomeSource source = ownedSource(id);

        IncomeCadence cadence = request.getCadence() != null ? request.getCadence() : source.getCadence();
        Money expected = source.getExpectedAmount();
        if (request.isExpectedAmountPresent()) {
            expected = positiveOrNull(request.getExpectedAmount(), "expectedAmount");
            if (expected != null) {
                requireSupportedCurrency(expected.currency());
            }
        }
        requireExpectedAmount(cadence, expected);

        if (request.getName() != null) {
            source.rename(request.getName().strip());
        }
        if (request.getType() != null) {
            source.changeType(request.getType());
        }
        source.changeExpectation(cadence, expected);
        if (request.getActive() != null) {
            source.setActive(request.getActive());
        }
        return IncomeSourceResponse.from(source);
    }

    /**
     * Deletes a source. A source with entries is deactivated so past entries stay explainable; an unused one
     * is removed.
     *
     * @param id the source id
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional
    public void deleteSource(UUID id) {
        IncomeSource source = ownedSource(id);
        if (entries.existsBySourceId(source.getId())) {
            source.deactivate();
        } else {
            sources.delete(source);
        }
    }

    // ------------------------------------------------------------------ entries

    /**
     * Lists the caller's entries with optional filters, attaching each entry's source name and type and
     * whether it has a payslip (batched, not per row).
     *
     * @param sourceId only entries of this source; {@code null} for all
     * @param types    only entries whose source has one of these types; {@code null} or empty for all
     * @param from     earliest {@code receivedOn}, inclusive; {@code null} for no lower bound
     * @param to       latest {@code receivedOn}, inclusive; {@code null} for no upper bound
     * @param page     zero-based page index
     * @param size     page size
     * @param sort     {@code field,asc|desc} entries; {@code null} or empty sorts by received date descending
     * @return one page of entries
     * @throws BadRequestException if {@code to} is before {@code from} or a sort field or direction is invalid
     */
    @Transactional(readOnly = true)
    public PageResponse<IncomeEntryResponse> listEntries(UUID sourceId, Collection<IncomeType> types,
                                                         LocalDate from, LocalDate to,
                                                         int page, int size, List<String> sort) {
        UUID userId = currentUser.currentUser().getId();
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("'to' must be on or after 'from'");
        }
        Specification<IncomeEntry> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("userId"), userId));
            if (sourceId != null) {
                where.add(cb.equal(root.get("sourceId"), sourceId));
            }
            if (types != null && !types.isEmpty()) {
                Subquery<UUID> sub = query.subquery(UUID.class);
                Root<IncomeSource> src = sub.from(IncomeSource.class);
                sub.select(src.get("id")).where(cb.equal(src.get("userId"), userId), src.get("type").in(types));
                where.add(root.get("sourceId").in(sub));
            }
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("receivedOn"), from));
            }
            if (to != null) {
                where.add(cb.lessThanOrEqualTo(root.get("receivedOn"), to));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(page, size,
            parseSort(sort, ENTRY_SORTABLE, Sort.Order.desc("receivedOn"), "income entries"));
        var result = entries.findAll(spec, pageable);

        List<UUID> ids = result.stream().map(IncomeEntry::getId).toList();
        Map<UUID, IncomeSource> sourcesById = sources
            .findByIdIn(result.stream().map(IncomeEntry::getSourceId).distinct().toList()).stream()
            .collect(Collectors.toMap(IncomeSource::getId, Function.identity()));
        Set<UUID> withPayslip = ids.isEmpty() ? Set.of() : payslips.findByIncomeEntryIdIn(ids).stream()
            .map(PayslipDocument::getIncomeEntryId).collect(Collectors.toSet());
        return PageResponse.of(result,
            e -> IncomeEntryResponse.from(e, sourcesById.get(e.getSourceId()), withPayslip.contains(e.getId())));
    }

    /**
     * Returns one of the caller's entries.
     *
     * @param id the entry id
     * @return the entry
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional(readOnly = true)
    public IncomeEntryResponse getEntry(UUID id) {
        return toResponse(ownedEntry(id));
    }

    /**
     * Records a manual entry against one of the caller's sources.
     *
     * @param request the new entry
     * @return the created entry, with origin {@code MANUAL} and no payslip
     * @throws ResourceNotFoundException if the source does not exist or belongs to someone else
     * @throws BadRequestException if an amount is not positive or the period ends before it starts
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY}, {@code NET_EXCEEDS_GROSS} or
     *         {@code INCOME_PERIOD_OVERLAP}
     */
    @Transactional
    public IncomeEntryResponse createEntry(CreateIncomeEntryRequest request) {
        UUID userId = currentUser.currentUser().getId();
        IncomeSource source = ownedSource(request.sourceId());
        Money gross = positive(request.grossAmount(), "grossAmount");
        Money net = positive(request.netAmount(), "netAmount");
        validate(source, request.periodStart(), request.periodEnd(), gross, net, null);

        IncomeEntry entry = new IncomeEntry(source.getId(), userId, request.periodStart(), request.periodEnd(),
            gross, net, request.receivedOn(), null, IncomeOrigin.MANUAL);
        return IncomeEntryResponse.from(entries.save(entry), source, false);
    }

    /**
     * Applies a partial update to a manual entry; omitted fields keep their stored values and the merged
     * result is validated as a whole (re-saving an entry's own period is not an overlap).
     *
     * @param id the entry id
     * @param request the fields to change
     * @return the updated entry
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     * @throws ConflictException {@code SOURCE_READ_ONLY} for an entry detected from the bank
     * @throws BadRequestException if an amount is not positive or the period ends before it starts
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY}, {@code NET_EXCEEDS_GROSS} or
     *         {@code INCOME_PERIOD_OVERLAP}
     */
    @Transactional
    public IncomeEntryResponse updateEntry(UUID id, UpdateIncomeEntryRequest request) {
        IncomeEntry entry = ownedEntry(id);
        if (entry.getOrigin() == IncomeOrigin.AA_DETECTED) {
            throw new ConflictException("SOURCE_READ_ONLY", "Entries detected from your bank can't be edited");
        }
        IncomeSource source = ownedSource(entry.getSourceId());

        LocalDate start = request.periodStart() != null ? request.periodStart() : entry.getPeriodStart();
        LocalDate end = request.periodEnd() != null ? request.periodEnd() : entry.getPeriodEnd();
        Money gross = request.grossAmount() != null ? positive(request.grossAmount(), "grossAmount")
            : entry.getGrossAmount();
        Money net = request.netAmount() != null ? positive(request.netAmount(), "netAmount") : entry.getNetAmount();
        LocalDate receivedOn = request.receivedOn() != null ? request.receivedOn() : entry.getReceivedOn();
        validate(source, start, end, gross, net, entry.getId());

        entry.revise(start, end, gross, net, receivedOn);
        return toResponse(entry, source);
    }

    /**
     * Deletes a manual entry.
     *
     * @param id the entry id
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     * @throws ConflictException {@code SOURCE_READ_ONLY} for an entry detected from the bank
     */
    @Transactional
    public void deleteEntry(UUID id) {
        IncomeEntry entry = ownedEntry(id);
        if (entry.getOrigin() == IncomeOrigin.AA_DETECTED) {
            throw new ConflictException("SOURCE_READ_ONLY", "Entries detected from your bank can't be deleted");
        }
        entries.delete(entry);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Loads a source owned by the caller.
     *
     * @param id the source id
     * @return the source
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    private IncomeSource ownedSource(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return sources.findByIdAndUserId(id, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Income source", id));
    }

    /**
     * Loads an entry owned by the caller.
     *
     * @param id the entry id
     * @return the entry
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    private IncomeEntry ownedEntry(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return entries.findByIdAndUserId(id, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Income entry", id));
    }

    /**
     * Maps an entry to its response, loading its (owned) source.
     *
     * @param entry the entry
     * @return the response
     */
    private IncomeEntryResponse toResponse(IncomeEntry entry) {
        return toResponse(entry, ownedSource(entry.getSourceId()));
    }

    /**
     * Maps an entry to its response using an already loaded source, querying whether a payslip exists.
     *
     * @param entry the entry
     * @param source the entry's source
     * @return the response
     */
    private IncomeEntryResponse toResponse(IncomeEntry entry, IncomeSource source) {
        return IncomeEntryResponse.from(entry, source, !payslips.findByIncomeEntryId(entry.getId()).isEmpty());
    }

    /**
     * Checks an entry's combined values against its source and its sibling entries.
     *
     * @param source the entry's source
     * @param start  first covered day
     * @param end    last covered day
     * @param gross  gross amount
     * @param net    net amount
     * @param selfId the entry being edited so it is not compared with itself, or {@code null} on create
     * @throws BadRequestException if the period ends before it starts
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY} if an amount is not in the source's
     *         currency, {@code NET_EXCEEDS_GROSS}, or {@code INCOME_PERIOD_OVERLAP} with another entry of the source
     */
    private void validate(IncomeSource source, LocalDate start, LocalDate end, Money gross, Money net, UUID selfId) {
        if (end.isBefore(start)) {
            throw new BadRequestException("periodEnd must be on or after periodStart");
        }
        if (!gross.currency().equals(source.getCurrency()) || !net.currency().equals(source.getCurrency())) {
            throw new UnprocessableEntityException(
                "UNSUPPORTED_CURRENCY", "Amounts must be in the source's currency (" + source.getCurrency() + ")");
        }
        if (net.isGreaterThan(gross)) {
            throw new UnprocessableEntityException("NET_EXCEEDS_GROSS", "Net amount can't exceed the gross amount");
        }
        // the nil UUID matches no row, so create can use the same query with nothing to exclude
        UUID exclude = selfId != null ? selfId : new UUID(0, 0);
        if (entries.existsOverlapping(source.getId(), exclude, start, end)) {
            throw new UnprocessableEntityException(
                "INCOME_PERIOD_OVERLAP", "This period overlaps another entry for the same source");
        }
    }

    /**
     * Enforces that every cadence except {@code IRREGULAR} has an expected amount.
     *
     * @param cadence the resulting cadence
     * @param expected the resulting expected amount, may be {@code null}
     * @throws UnprocessableEntityException {@code EXPECTED_AMOUNT_REQUIRED} when it is missing
     */
    private static void requireExpectedAmount(IncomeCadence cadence, Money expected) {
        if (cadence != IncomeCadence.IRREGULAR && expected == null) {
            throw new UnprocessableEntityException(
                "EXPECTED_AMOUNT_REQUIRED", "expectedAmount is required unless cadence is IRREGULAR");
        }
    }

    /**
     * Enforces the INR-only rule.
     *
     * @param currency an ISO 4217 code
     * @throws UnprocessableEntityException {@code UNSUPPORTED_CURRENCY} for anything but INR
     */
    private static void requireSupportedCurrency(String currency) {
        if (!SUPPORTED_CURRENCY.equals(currency)) {
            throw new UnprocessableEntityException("UNSUPPORTED_CURRENCY", "Only INR is supported");
        }
    }

    /**
     * Converts an optional amount, passing {@code null} through.
     *
     * @param dto the amount, may be {@code null}
     * @param field the request field name, used in the error message
     * @return the amount, or {@code null} if none was given
     * @throws BadRequestException if given but not positive
     */
    private static Money positiveOrNull(MoneyDto dto, String field) {
        return dto == null ? null : positive(dto, field);
    }

    /**
     * Converts a required amount and checks it is greater than zero.
     *
     * @param dto the amount
     * @param field the request field name, used in the error message
     * @return the amount
     * @throws BadRequestException if it is zero or negative
     */
    private static Money positive(MoneyDto dto, String field) {
        Money money = dto.toMoney();
        if (money.amount().signum() <= 0) {
            throw new BadRequestException(field + " must be greater than zero");
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
