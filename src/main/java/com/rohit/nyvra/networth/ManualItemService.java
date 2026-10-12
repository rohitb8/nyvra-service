package com.rohit.nyvra.networth;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rohit.nyvra.common.api.PageResponse;
import com.rohit.nyvra.common.exception.ResourceNotFoundException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.networth.dto.CreateManualItemRequest;
import com.rohit.nyvra.networth.dto.ManualItemResponse;
import com.rohit.nyvra.networth.dto.UpdateManualItemRequest;
import com.rohit.nyvra.user.CurrentUserService;

/**
 * Use cases for hand-maintained assets and liabilities. Every lookup is scoped to the caller, so another user's
 * entry is indistinguishable from a missing one (404, never 403). Each change re-records today's net-worth
 * snapshot so the trend follows the entries.
 */
@Service
public class ManualItemService {

    /** The only currency supported in v1 (API_DESIGN §3). */
    private static final String SUPPORTED_CURRENCY = Money.INR;

    /** Classes that only make sense for assets. */
    private static final Set<ManualItemClass> ASSET_ONLY = Set.of(ManualItemClass.REAL_ESTATE,
        ManualItemClass.VEHICLE, ManualItemClass.JEWELLERY, ManualItemClass.PRIVATE_EQUITY);

    /** Entry persistence. */
    private final ManualAssetLiabilityRepository items;
    /** Re-records the snapshot after a change. */
    private final NetWorthService netWorth;
    /** Resolves the signed-in user that every query is scoped to. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param items       the entry repository
     * @param netWorth    the net-worth service, used to refresh the snapshot
     * @param currentUser the signed-in user resolver
     */
    public ManualItemService(ManualAssetLiabilityRepository items, NetWorthService netWorth,
                             CurrentUserService currentUser) {
        this.items = items;
        this.netWorth = netWorth;
        this.currentUser = currentUser;
    }

    /**
     * Lists the caller's entries, oldest first.
     *
     * @param kind optional filter on asset or liability
     * @param page zero-based page
     * @param size page size
     * @return the page
     */
    @Transactional(readOnly = true)
    public PageResponse<ManualItemResponse> list(ManualItemKind kind, int page, int size) {
        UUID userId = currentUser.currentUser().getId();
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt", "id"));
        Page<ManualAssetLiability> result = kind == null
            ? items.findByUserIdAndDeletedAtIsNull(userId, pageable)
            : items.findByUserIdAndKindAndDeletedAtIsNull(userId, kind, pageable);
        LocalDate today = NetWorthService.today();
        return PageResponse.of(result, i -> ManualItemResponse.from(i, today));
    }

    /**
     * Returns one entry.
     *
     * @param id the entry id
     * @return the entry
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional(readOnly = true)
    public ManualItemResponse get(UUID id) {
        return ManualItemResponse.from(owned(id), NetWorthService.today());
    }

    /**
     * Adds an entry.
     *
     * @param request the new entry
     * @return the created entry
     * @throws UnprocessableEntityException if the currency is not INR or the class does not fit the kind
     */
    @Transactional
    public ManualItemResponse create(CreateManualItemRequest request) {
        UUID userId = currentUser.currentUser().getId();
        Money value = request.value().toMoney();
        requireSupportedCurrency(value);
        requireNotNegative(value);
        requireClassFits(request.kind(), request.itemClass());
        LocalDate asOf = request.valueAsOf() != null ? request.valueAsOf() : NetWorthService.today();
        requireNotFuture(asOf);

        ManualAssetLiability item = items.save(new ManualAssetLiability(userId, request.kind(),
            request.itemClass(), request.label(), value, asOf, cadence(request.revaluationCadenceDays())));
        netWorth.recordSnapshot(userId);
        return ManualItemResponse.from(item, NetWorthService.today());
    }

    /**
     * Updates an entry; omitted fields are unchanged.
     *
     * @param id      the entry id
     * @param request the changes
     * @return the updated entry
     * @throws ResourceNotFoundException    if it does not exist or belongs to someone else
     * @throws UnprocessableEntityException if the new value or class is not allowed
     */
    @Transactional
    public ManualItemResponse update(UUID id, UpdateManualItemRequest request) {
        ManualAssetLiability item = owned(id);
        if (request.itemClass() != null) {
            requireClassFits(item.getKind(), request.itemClass());
            item.changeClass(request.itemClass());
        }
        if (request.label() != null) {
            item.relabel(request.label());
        }
        if (request.value() != null) {
            Money value = request.value().toMoney();
            requireSupportedCurrency(value);
            requireNotNegative(value);
            LocalDate asOf = request.valueAsOf() != null ? request.valueAsOf() : NetWorthService.today();
            requireNotFuture(asOf);
            item.revalue(value, asOf);
        } else if (request.valueAsOf() != null) {
            requireNotFuture(request.valueAsOf());
            item.revalue(item.getValue(), request.valueAsOf());
        }
        if (request.revaluationCadenceDays() != null) {
            item.changeCadence(cadence(request.revaluationCadenceDays()));
        }
        netWorth.recordSnapshot(item.getUserId());
        return ManualItemResponse.from(item, NetWorthService.today());
    }

    /**
     * Soft-deletes an entry so it stops counting towards net worth.
     *
     * @param id the entry id
     * @throws ResourceNotFoundException if it does not exist or belongs to someone else
     */
    @Transactional
    public void delete(UUID id) {
        ManualAssetLiability item = owned(id);
        item.softDelete(Instant.now());
        netWorth.recordSnapshot(item.getUserId());
    }

    /**
     * Loads one of the caller's live entries.
     *
     * @param id the entry id
     * @return the entity
     */
    private ManualAssetLiability owned(UUID id) {
        UUID userId = currentUser.currentUser().getId();
        return items.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Manual item", id));
    }

    /**
     * Converts a cadence in days to a duration.
     *
     * @param days the days, or {@code null} for never stale
     * @return the duration, or {@code null}
     */
    private static Duration cadence(Integer days) {
        return days == null ? null : Duration.ofDays(days);
    }

    /**
     * Rejects non-INR values.
     *
     * @param value the value
     */
    private static void requireSupportedCurrency(Money value) {
        if (!SUPPORTED_CURRENCY.equals(value.currency())) {
            throw new UnprocessableEntityException("UNSUPPORTED_CURRENCY", "Only INR is supported");
        }
    }

    /**
     * Rejects negative values; a liability is entered as the positive amount owed.
     *
     * @param value the value
     */
    private static void requireNotNegative(Money value) {
        if (value.isNegative()) {
            throw new UnprocessableEntityException("NEGATIVE_VALUE", "Value can't be negative");
        }
    }

    /**
     * Rejects valuations dated in the future.
     *
     * @param asOf the valuation date
     */
    private static void requireNotFuture(LocalDate asOf) {
        if (asOf.isAfter(NetWorthService.today())) {
            throw new UnprocessableEntityException("VALUE_DATE_IN_FUTURE", "valueAsOf can't be in the future");
        }
    }

    /**
     * Checks that a class can describe the kind: property, vehicles, jewellery and private equity are assets,
     * a personal loan is a liability, {@code OTHER} fits both.
     *
     * @param kind the kind
     * @param cls  the class
     */
    private static void requireClassFits(ManualItemKind kind, ManualItemClass cls) {
        boolean ok = kind == ManualItemKind.ASSET
            ? cls != ManualItemClass.PERSONAL_LOAN
            : !ASSET_ONLY.contains(cls);
        if (!ok) {
            throw new UnprocessableEntityException("CLASS_KIND_MISMATCH",
                cls + " can't be used for a " + kind);
        }
    }
}
