package com.rohit.nyvra.networth;

import java.util.List;

import com.rohit.nyvra.common.money.Money;

/**
 * The result of valuing everything a user owns and owes on one day. Internal to the module; controllers map
 * it to DTOs.
 *
 * @param totalAssets      sum of all asset lines
 * @param totalLiabilities sum of all liability lines, as a positive amount
 * @param lines            one line per non-empty class, assets first
 * @param sources          one entry per source module, including empty ones
 * @param staleItemCount   accounts and manual entries whose value is older than it should be
 */
public record NetWorthComputation(
    Money totalAssets,
    Money totalLiabilities,
    List<Line> lines,
    List<SourceTotal> sources,
    int staleItemCount) {

    /**
     * Net worth: assets minus liabilities.
     *
     * @return the difference, negative when the user owes more than they own
     */
    public Money netWorth() {
        return totalAssets.minus(totalLiabilities);
    }

    /**
     * One breakdown line.
     *
     * @param netWorthClass the bucket
     * @param amount        the bucket total, positive for both assets and liabilities unless an overdrawn
     *                      account makes a cash line negative
     */
    public record Line(NetWorthClass netWorthClass, Money amount) {
    }

    /**
     * What one source module contributed.
     *
     * @param source      the module
     * @param assets      its asset total
     * @param liabilities its liability total
     * @param itemCount   how many accounts, holdings groups or manual entries fed it
     */
    public record SourceTotal(NetWorthSource source, Money assets, Money liabilities, int itemCount) {
    }
}
