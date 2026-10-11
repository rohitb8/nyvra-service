package com.rohit.nyvra.networth;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.rohit.nyvra.accounts.AccountStatus;
import com.rohit.nyvra.accounts.AccountType;
import com.rohit.nyvra.accounts.FinancialAccount;
import com.rohit.nyvra.common.money.Money;

/**
 * Pure arithmetic that turns accounts, manual entries and a portfolio value into a {@link NetWorthComputation}.
 * It touches no database, so it is unit-tested directly. All amounts are INR (the only v1 currency).
 *
 * <p>Rules: closed accounts are excluded; loan and card balances count as liabilities by magnitude, whichever
 * sign the source stored them with; every other account balance counts as an asset as stored.
 */
public final class NetWorthCalculator {

    /** Utility class; not instantiable. */
    private NetWorthCalculator() {
    }

    /**
     * Values everything a user owns and owes.
     *
     * @param accounts       the user's live accounts, in any status
     * @param manualItems    the user's live manual entries
     * @param portfolioValue the market value of the user's holdings
     * @param today          the day used to judge staleness
     * @return the computation
     */
    public static NetWorthComputation calculate(List<FinancialAccount> accounts, List<ManualAssetLiability> manualItems,
                                                Money portfolioValue, LocalDate today) {
        Map<NetWorthClass, Money> byClass = new EnumMap<>(NetWorthClass.class);
        Money accountAssets = Money.zero(Money.INR);
        Money accountLiabilities = Money.zero(Money.INR);
        Money manualAssets = Money.zero(Money.INR);
        Money manualLiabilities = Money.zero(Money.INR);
        int accountCount = 0;
        int manualCount = 0;
        int stale = 0;

        for (FinancialAccount account : accounts) {
            if (account.getStatus() == AccountStatus.CLOSED) {
                continue;
            }
            accountCount++;
            if (account.getStatus() == AccountStatus.STALE) {
                stale++;
            }
            NetWorthClass cls = classOf(account.getType());
            Money amount = cls.isLiability() ? absolute(account.getCurrentBalance()) : account.getCurrentBalance();
            byClass.merge(cls, amount, Money::plus);
            if (cls.isLiability()) {
                accountLiabilities = accountLiabilities.plus(amount);
            } else {
                accountAssets = accountAssets.plus(amount);
            }
        }

        for (ManualAssetLiability item : manualItems) {
            manualCount++;
            if (item.isStale(today)) {
                stale++;
            }
            NetWorthClass cls = NetWorthClass.of(item.getKind(), item.getItemClass());
            byClass.merge(cls, item.getValue(), Money::plus);
            if (cls.isLiability()) {
                manualLiabilities = manualLiabilities.plus(item.getValue());
            } else {
                manualAssets = manualAssets.plus(item.getValue());
            }
        }

        if (portfolioValue.amount().signum() != 0) {
            byClass.merge(NetWorthClass.INVESTMENTS, portfolioValue, Money::plus);
        }

        List<NetWorthComputation.Line> lines = new ArrayList<>();
        for (NetWorthClass cls : NetWorthClass.values()) {
            Money amount = byClass.get(cls);
            if (amount != null) {
                lines.add(new NetWorthComputation.Line(cls, amount));
            }
        }

        List<NetWorthComputation.SourceTotal> sources = List.of(
            new NetWorthComputation.SourceTotal(NetWorthSource.ACCOUNTS, accountAssets, accountLiabilities,
                accountCount),
            new NetWorthComputation.SourceTotal(NetWorthSource.PORTFOLIO, portfolioValue, Money.zero(Money.INR),
                portfolioValue.amount().signum() == 0 ? 0 : 1),
            new NetWorthComputation.SourceTotal(NetWorthSource.MANUAL, manualAssets, manualLiabilities,
                manualCount));

        Money totalAssets = accountAssets.plus(manualAssets).plus(portfolioValue);
        Money totalLiabilities = accountLiabilities.plus(manualLiabilities);
        return new NetWorthComputation(totalAssets, totalLiabilities, lines, sources, stale);
    }

    /**
     * Maps an account type to its breakdown class.
     *
     * @param type the account type
     * @return the class
     */
    static NetWorthClass classOf(AccountType type) {
        return switch (type) {
            case SAVINGS, CURRENT -> NetWorthClass.CASH;
            case TERM_DEPOSIT, RECURRING_DEPOSIT -> NetWorthClass.DEPOSITS;
            case EPF, NPS -> NetWorthClass.RETIREMENT;
            case LOAN -> NetWorthClass.LOAN;
            case CREDIT_CARD -> NetWorthClass.CREDIT_CARD;
        };
    }

    /**
     * Takes the magnitude of an amount.
     *
     * @param money the amount
     * @return the amount, negated when negative
     */
    private static Money absolute(Money money) {
        return money.isNegative() ? money.negate() : money;
    }
}
