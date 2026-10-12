package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.portfolio.dto.ReturnsResponse;
import com.rohit.nyvra.portfolio.dto.ReturnsResponse.HoldingReturn;
import com.rohit.nyvra.portfolio.dto.ValuationHistoryResponse;
import com.rohit.nyvra.portfolio.dto.ValuationHistoryResponse.Interval;
import com.rohit.nyvra.portfolio.dto.ValuationHistoryResponse.Point;
import com.rohit.nyvra.user.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only portfolio analytics: annualised returns and value over time. Kept apart from
 * {@link PortfolioService} so that service's public surface stays stable for other modules.
 */
@Service
public class PortfolioAnalyticsService {

    /** The only currency holdings are valued in. */
    private static final String CURRENCY = Money.INR;

    /** Display time zone, which decides where a "day" ends. */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    /** Most points one history response may hold. */
    private static final int MAX_POINTS = 400;

    /** Days covered by a history request that names no start. */
    private static final int DEFAULT_WINDOW_DAYS = 29;

    /** Days between weekly points. */
    private static final int WEEK = 7;

    /** Holding storage. */
    private final PortfolioHoldingRepository holdings;

    /** Quote storage. */
    private final PriceQuoteRepository quotes;

    /** Resolves the caller. */
    private final CurrentUserService currentUser;

    /**
     * Creates the service.
     *
     * @param holdings    holding storage
     * @param quotes      quote storage
     * @param currentUser resolves the calling user
     */
    public PortfolioAnalyticsService(PortfolioHoldingRepository holdings, PriceQuoteRepository quotes,
                                     CurrentUserService currentUser) {
        this.holdings = holdings;
        this.quotes = quotes;
        this.currentUser = currentUser;
    }

    /**
     * Computes the XIRR of the caller's open INR holdings, overall and per holding.
     *
     * @return the returns; the overall rate is omitted when undefined
     */
    @Transactional(readOnly = true)
    public ReturnsResponse returns() {
        UUID userId = currentUser.currentUser().getId();
        List<PortfolioHolding> inr = holdings.findByUserIdAndClosedAtIsNull(userId).stream()
            .filter(h -> CURRENCY.equals(h.getCurrency())).toList();
        Map<UUID, PriceQuote> latest = latestQuotes(inr);
        Instant now = Instant.now();

        List<Xirr.CashFlow> all = new ArrayList<>();
        List<HoldingReturn> perHolding = new ArrayList<>();
        for (PortfolioHolding h : inr) {
            PriceQuote quote = latest.get(h.getInstrumentId());
            Money cost = PortfolioValuation.invested(h.getQuantity(), h.getAvgCost(), CURRENCY);
            Money value = quote == null ? null : PortfolioValuation.current(h.getQuantity(), quote.getPrice(), CURRENCY);
            if (cost == null || value == null) {
                continue;
            }
            List<Xirr.CashFlow> flows = List.of(
                new Xirr.CashFlow(h.getOpenedAt(), cost.amount().negate()),
                new Xirr.CashFlow(now, value.amount()));
            all.addAll(flows);
            perHolding.add(new HoldingReturn(h.getId(), h.getInstrumentId(), Xirr.compute(flows).orElse(null)));
        }
        int excluded = holdings.findByUserIdAndClosedAtIsNull(userId).size() - perHolding.size();
        return new ReturnsResponse(Xirr.compute(all).orElse(null), perHolding.size(), excluded, now, perHolding);
    }

    /**
     * Values the caller's open INR holdings at the end of each day in a range.
     *
     * @param from     first day, inclusive; defaults to 29 days before {@code to}
     * @param to       last day, inclusive; defaults to today (Asia/Kolkata)
     * @param interval spacing between points; defaults to daily
     * @return the history, oldest point first
     */
    @Transactional(readOnly = true)
    public ValuationHistoryResponse valuationHistory(LocalDate from, LocalDate to, Interval interval) {
        LocalDate end = to != null ? to : LocalDate.now(DISPLAY_ZONE);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_WINDOW_DAYS);
        Interval step = interval != null ? interval : Interval.DAILY;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must not be after to");
        }
        List<LocalDate> dates = dates(start, end, step);
        if (dates.size() > MAX_POINTS) {
            throw new BadRequestException("That range holds " + dates.size() + " points; ask for at most "
                + MAX_POINTS + " or use a wider interval");
        }

        UUID userId = currentUser.currentUser().getId();
        List<PortfolioHolding> inr = holdings.findByUserIdAndClosedAtIsNull(userId).stream()
            .filter(h -> CURRENCY.equals(h.getCurrency())).toList();
        Map<UUID, TreeMap<Instant, BigDecimal>> prices = priceSeries(inr, endOfDay(start), endOfDay(end));

        List<Point> points = new ArrayList<>();
        for (LocalDate date : dates) {
            Instant cutoff = endOfDay(date);
            Money value = Money.zero(CURRENCY);
            Money invested = Money.zero(CURRENCY);
            int priced = 0;
            int unpriced = 0;
            for (PortfolioHolding h : inr) {
                if (h.getOpenedAt().isAfter(cutoff)) {
                    continue;
                }
                Money cost = PortfolioValuation.invested(h.getQuantity(), h.getAvgCost(), CURRENCY);
                if (cost != null) {
                    invested = invested.plus(cost);
                }
                var series = prices.get(h.getInstrumentId());
                var entry = series == null ? null : series.floorEntry(cutoff);
                if (entry == null) {
                    unpriced++;
                } else {
                    priced++;
                    value = value.plus(PortfolioValuation.current(h.getQuantity(), entry.getValue(), CURRENCY));
                }
            }
            points.add(new Point(date, MoneyDto.from(value), MoneyDto.from(invested), priced, unpriced));
        }
        return new ValuationHistoryResponse(CURRENCY, step, points);
    }

    /**
     * Lists the days a history covers.
     *
     * @param start first day
     * @param end   last day
     * @param step  spacing
     * @return the dates, oldest first, always ending on {@code end}
     */
    static List<LocalDate> dates(LocalDate start, LocalDate end, Interval step) {
        List<LocalDate> dates = new ArrayList<>();
        switch (step) {
            case DAILY -> {
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    dates.add(d);
                }
            }
            case WEEKLY -> {
                for (LocalDate d = end; !d.isBefore(start); d = d.minusDays(WEEK)) {
                    dates.add(0, d);
                }
            }
            case MONTHLY -> {
                for (YearMonth m = YearMonth.from(start); !m.atEndOfMonth().isAfter(end); m = m.plusMonths(1)) {
                    if (!m.atEndOfMonth().isBefore(start)) {
                        dates.add(m.atEndOfMonth());
                    }
                }
                if (dates.isEmpty() || !dates.get(dates.size() - 1).equals(end)) {
                    dates.add(end);
                }
            }
            default -> throw new IllegalStateException("Unhandled interval " + step);
        }
        return dates;
    }

    /**
     * The last moment of a day in the display time zone.
     *
     * @param date the day
     * @return the final microsecond of that day
     */
    static Instant endOfDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(DISPLAY_ZONE).toInstant().minusNanos(1_000);
    }

    /**
     * Builds each instrument's price series covering a window: the price in force at its start plus every
     * quote inside it.
     *
     * @param list  the holdings to price
     * @param first the window's start
     * @param last  the window's end
     * @return prices keyed by instrument, then by quote time
     */
    private Map<UUID, TreeMap<Instant, BigDecimal>> priceSeries(List<PortfolioHolding> list, Instant first,
                                                                 Instant last) {
        List<UUID> ids = list.stream().map(PortfolioHolding::getInstrumentId).distinct().toList();
        Map<UUID, TreeMap<Instant, BigDecimal>> series = new HashMap<>();
        if (ids.isEmpty()) {
            return series;
        }
        quotes.findLatestBefore(ids, first).forEach(q -> add(series, q));
        quotes.findByInstrumentIdInAndAsOfBetweenOrderByAsOfAsc(ids, first, last).forEach(q -> add(series, q));
        return series;
    }

    /**
     * Adds a quote to its instrument's series.
     *
     * @param series the series being built
     * @param quote  the quote to add
     */
    private static void add(Map<UUID, TreeMap<Instant, BigDecimal>> series, PriceQuote quote) {
        series.computeIfAbsent(quote.getInstrumentId(), k -> new TreeMap<>()).put(quote.getAsOf(), quote.getPrice());
    }

    /**
     * Loads the latest quote of each holding's instrument.
     *
     * @param list the holdings
     * @return latest quotes keyed by instrument; unpriced instruments are absent
     */
    private Map<UUID, PriceQuote> latestQuotes(Collection<PortfolioHolding> list) {
        List<UUID> ids = list.stream().map(PortfolioHolding::getInstrumentId).distinct().toList();
        Map<UUID, PriceQuote> byInstrument = new HashMap<>();
        if (!ids.isEmpty()) {
            quotes.findLatestByInstrumentIds(ids).forEach(q -> byInstrument.put(q.getInstrumentId(), q));
        }
        return byInstrument;
    }
}
