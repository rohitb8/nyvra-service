package com.rohit.nyvra.income;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.income.dto.IncomeSummaryResponse;
import com.rohit.nyvra.user.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the rolling income summary shown on the Income screen. The window ends today (Asia/Kolkata) and
 * starts the same day-of-month {@code window} months earlier plus one day, so a 3-month window on 10 Oct covers
 * 11 Jul to 10 Oct. Entries count by the date the money was received. Amounts are INR (the only v1 currency).
 */
@Service
public class IncomeSummaryService {

    /** Accepted window lengths in months. */
    static final Set<Integer> WINDOWS = Set.of(3, 6, 12);

    /** The user's display time zone, which decides what "today" is. */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    /** Entry persistence. */
    private final IncomeEntryRepository entries;
    /** Resolves the signed-in user that every query is scoped to. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param entries     the entry repository
     * @param currentUser the signed-in user resolver
     */
    public IncomeSummaryService(IncomeEntryRepository entries, CurrentUserService currentUser) {
        this.entries = entries;
        this.currentUser = currentUser;
    }

    /**
     * Summarises the caller's income over a trailing window ending today.
     *
     * @param windowMonths 3, 6 or 12
     * @return the summary
     * @throws BadRequestException if the window is not 3, 6 or 12
     */
    @Transactional(readOnly = true)
    public IncomeSummaryResponse summarize(int windowMonths) {
        return summarize(windowMonths, LocalDate.now(DISPLAY_ZONE));
    }

    /**
     * Summarises the caller's income over a trailing window ending on the given day.
     *
     * @param windowMonths 3, 6 or 12
     * @param today        last day of the window
     * @return the summary
     * @throws BadRequestException if the window is not 3, 6 or 12
     */
    @Transactional(readOnly = true)
    IncomeSummaryResponse summarize(int windowMonths, LocalDate today) {
        if (!WINDOWS.contains(windowMonths)) {
            throw new BadRequestException("window must be 3, 6 or 12");
        }
        LocalDate from = today.minusMonths(windowMonths).plusDays(1);
        List<IncomeEntryRepository.TypeTotals> rows = entries.sumByType(currentUser.currentUser().getId(), from, today);

        BigDecimal totalNet = rows.stream().map(IncomeEntryRepository.TypeTotals::getTotalNet)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalGross = rows.stream().map(IncomeEntryRepository.TypeTotals::getTotalGross)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<IncomeSummaryResponse.TypeTotal> byType = rows.stream()
            .sorted(Comparator.comparing(IncomeEntryRepository.TypeTotals::getTotalNet).reversed()
                .thenComparing(r -> r.getType().name()))
            .map(r -> new IncomeSummaryResponse.TypeTotal(r.getType(), inr(r.getTotalNet()),
                share(r.getTotalNet(), totalNet)))
            .toList();

        return new IncomeSummaryResponse(windowMonths, from, today,
            inr(average(totalNet, windowMonths)), inr(average(totalGross, windowMonths)), inr(totalNet),
            byType, Instant.now());
    }

    /**
     * Divides a total by the window length.
     *
     * @param total        the window total
     * @param windowMonths the window length
     * @return the monthly average, scale 2
     */
    private static BigDecimal average(BigDecimal total, int windowMonths) {
        return total.divide(BigDecimal.valueOf(windowMonths), Money.SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Computes a part's share of the whole in percent points.
     *
     * @param part  the part
     * @param whole the whole, positive when any part exists
     * @return the share with one decimal, or 0 when the whole is zero
     */
    private static double share(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return 0.0;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Wraps an amount as INR money on the wire.
     *
     * @param amount the amount
     * @return the DTO, scale 2
     */
    private static MoneyDto inr(BigDecimal amount) {
        return MoneyDto.from(Money.of(amount, Money.INR));
    }
}
