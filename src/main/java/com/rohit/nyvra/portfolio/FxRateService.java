package com.rohit.nyvra.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.rohit.nyvra.common.exception.BadRequestException;
import com.rohit.nyvra.common.exception.UnprocessableEntityException;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;
import com.rohit.nyvra.portfolio.dto.ConversionResponse;
import com.rohit.nyvra.portfolio.dto.FxRateResponse;
import com.rohit.nyvra.portfolio.dto.RecordFxRateRequest;
import com.rohit.nyvra.common.money.MoneyDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores daily FX rates against INR and converts money between currencies through them. Other modules call
 * {@link #convertMoney(Money, String, LocalDate)} to value foreign amounts in rupees.
 */
@Service
public class FxRateService {

    /** Display time zone, which decides what "today" means for a rate. */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");

    /** Scale of stored rates. */
    private static final int RATE_SCALE = 6;

    /** Widest range a rate listing may cover, in days. */
    private static final long MAX_RANGE_DAYS = 366;

    /** Rate storage. */
    private final FxRateRepository rates;

    /**
     * Creates the service.
     *
     * @param rates rate storage
     */
    public FxRateService(FxRateRepository rates) {
        this.rates = rates;
    }

    /**
     * Sets a day's rate, replacing any earlier rate for that currency and day.
     *
     * @param request the rate to store
     * @param source  where the rate came from
     * @return the stored rate
     */
    @Transactional
    public FxRateResponse record(RecordFxRateRequest request, RecordSource source) {
        return FxRateResponse.from(store(request.currency(), request.rateDate(),
            new BigDecimal(request.inrPerUnit()), source));
    }

    /**
     * Stores a rate from code (the price feed), replacing any earlier rate for that currency and day.
     *
     * @param currency   ISO 4217 code, not INR
     * @param date       the day the rate applies to, or {@code null} for today
     * @param inrPerUnit rupees per unit, above zero
     * @param source     where the rate came from
     * @return the stored rate
     */
    @Transactional
    public FxRate store(String currency, LocalDate date, BigDecimal inrPerUnit, RecordSource source) {
        if (Money.INR.equals(currency)) {
            throw new BadRequestException("INR has no rate against itself");
        }
        if (inrPerUnit.signum() <= 0) {
            throw new BadRequestException("rate must be above zero");
        }
        LocalDate today = LocalDate.now(DISPLAY_ZONE);
        LocalDate day = date != null ? date : today;
        if (day.isAfter(today)) {
            throw new UnprocessableEntityException("RATE_IN_FUTURE", "A rate cannot be dated in the future");
        }
        BigDecimal rate = inrPerUnit.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        FxRate row = rates.findById(new FxRateId(currency, day)).orElse(null);
        if (row == null) {
            return rates.save(new FxRate(currency, day, rate, source));
        }
        row.replace(rate, source);
        return row;
    }

    /**
     * Lists a currency's rates over a date range.
     *
     * @param currency ISO 4217 code
     * @param from     first day, inclusive; defaults to 30 days before {@code to}
     * @param to       last day, inclusive; defaults to today
     * @return the rates, oldest first
     */
    @Transactional(readOnly = true)
    public List<FxRateResponse> list(String currency, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(DISPLAY_ZONE);
        LocalDate start = from != null ? from : end.minusDays(30);
        if (start.isAfter(end)) {
            throw new BadRequestException("from must not be after to");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw new BadRequestException("The range may not exceed " + MAX_RANGE_DAYS + " days");
        }
        return rates.findByCurrencyAndRateDateBetweenOrderByRateDateAsc(currency, start, end).stream()
            .map(FxRateResponse::from).toList();
    }

    /**
     * Converts an amount, valuing it with the latest rate on or before a day.
     *
     * @param amount the amount to convert
     * @param target ISO 4217 code to convert into
     * @param date   the day to value on, or {@code null} for today
     * @return the converted amount and the rate date it relied on
     * @throws UnprocessableEntityException {@code FX_RATE_UNAVAILABLE} when a needed currency has no rate yet
     */
    @Transactional(readOnly = true)
    public ConversionResponse convert(Money amount, String target, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(DISPLAY_ZONE);
        if (amount.currency().equals(target)) {
            return new ConversionResponse(MoneyDto.from(amount), MoneyDto.from(amount), day, null);
        }
        BigDecimal inr = amount.amount();
        LocalDate rateDate = null;
        if (!Money.INR.equals(amount.currency())) {
            FxRate from = rateFor(amount.currency(), day);
            inr = inr.multiply(from.getInrPerUnit());
            rateDate = from.getRateDate();
        }
        BigDecimal converted = inr;
        if (!Money.INR.equals(target)) {
            FxRate to = rateFor(target, day);
            converted = inr.divide(to.getInrPerUnit(), 10, RoundingMode.HALF_UP);
            rateDate = rateDate == null || to.getRateDate().isBefore(rateDate) ? to.getRateDate() : rateDate;
        }
        Money result = Money.of(converted.setScale(Money.SCALE, RoundingMode.HALF_UP), target);
        return new ConversionResponse(MoneyDto.from(amount), MoneyDto.from(result), day, rateDate);
    }

    /**
     * Convenience for callers that only need the converted money.
     *
     * @param amount the amount to convert
     * @param target ISO 4217 code to convert into
     * @param date   the day to value on, or {@code null} for today
     * @return the converted amount
     * @throws UnprocessableEntityException {@code FX_RATE_UNAVAILABLE} when a needed currency has no rate yet
     */
    @Transactional(readOnly = true)
    public Money convertMoney(Money amount, String target, LocalDate date) {
        return convert(amount, target, date).to().toMoney();
    }

    /**
     * Finds the rate in force for a currency on a day.
     *
     * @param currency ISO 4217 code
     * @param day      the day to value on
     * @return the latest rate on or before that day
     */
    private FxRate rateFor(String currency, LocalDate day) {
        return rates.findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(currency, day)
            .orElseThrow(() -> new UnprocessableEntityException("FX_RATE_UNAVAILABLE",
                "No FX rate for " + currency + " on or before " + day));
    }
}
