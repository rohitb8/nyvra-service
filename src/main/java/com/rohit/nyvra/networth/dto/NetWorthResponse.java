package com.rohit.nyvra.networth.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.money.MoneyDto;
import com.rohit.nyvra.networth.NetWorthClass;
import com.rohit.nyvra.networth.NetWorthComputation;
import com.rohit.nyvra.networth.NetWorthSource;

/**
 * Net worth as of a moment, with its breakdown and where each part came from.
 *
 * @param asOf               when the figures were valued
 * @param totalAssets        everything owned
 * @param totalLiabilities   everything owed, as a positive amount
 * @param netWorth           assets minus liabilities; negative when liabilities exceed assets
 * @param breakdown          one line per non-empty class, assets first
 * @param contributingSources what each source module contributed
 * @param staleItemCount     accounts and manual entries whose value needs refreshing
 */
public record NetWorthResponse(
    Instant asOf,
    MoneyDto totalAssets,
    MoneyDto totalLiabilities,
    MoneyDto netWorth,
    List<BreakdownLine> breakdown,
    List<SourceContribution> contributingSources,
    int staleItemCount) {

    /**
     * One class in the breakdown.
     *
     * @param bucket        the bucket
     * @param liability     whether the bucket counts against net worth
     * @param amount        the bucket total
     * @param sharePercent  the bucket's share of its own side (assets or liabilities) in percent points, one
     *                      decimal; 0 when that side totals zero
     */
    public record BreakdownLine(NetWorthClass bucket, boolean liability, MoneyDto amount,
                                double sharePercent) {
    }

    /**
     * What one source module contributed.
     *
     * @param source      the module
     * @param assets      its asset total
     * @param liabilities its liability total
     * @param itemCount   how many items fed it
     */
    public record SourceContribution(NetWorthSource source, MoneyDto assets, MoneyDto liabilities, int itemCount) {
    }

    /**
     * Maps a computation to its wire form.
     *
     * @param c    the computation
     * @param asOf when it was valued
     * @return the response
     */
    public static NetWorthResponse from(NetWorthComputation c, Instant asOf) {
        List<BreakdownLine> lines = c.lines().stream()
            .map(l -> new BreakdownLine(l.netWorthClass(), l.netWorthClass().isLiability(),
                MoneyDto.from(l.amount()),
                share(l.amount(), l.netWorthClass().isLiability() ? c.totalLiabilities() : c.totalAssets())))
            .toList();
        List<SourceContribution> sources = c.sources().stream()
            .map(s -> new SourceContribution(s.source(), MoneyDto.from(s.assets()), MoneyDto.from(s.liabilities()),
                s.itemCount()))
            .toList();
        return new NetWorthResponse(asOf, MoneyDto.from(c.totalAssets()), MoneyDto.from(c.totalLiabilities()),
            MoneyDto.from(c.netWorth()), lines, sources, c.staleItemCount());
    }

    /**
     * Computes a part's share of its side in percent points.
     *
     * @param part  the part
     * @param whole the side's total
     * @return the share with one decimal, or 0 when the whole is zero
     */
    private static double share(Money part, Money whole) {
        if (whole.amount().signum() == 0) {
            return 0.0;
        }
        return part.amount().multiply(BigDecimal.valueOf(100))
            .divide(whole.amount(), 1, RoundingMode.HALF_UP).doubleValue();
    }
}
