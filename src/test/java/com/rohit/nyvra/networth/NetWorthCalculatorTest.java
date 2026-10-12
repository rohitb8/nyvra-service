package com.rohit.nyvra.networth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rohit.nyvra.accounts.AccountStatus;
import com.rohit.nyvra.accounts.AccountType;
import com.rohit.nyvra.accounts.FinancialAccount;
import com.rohit.nyvra.common.money.Money;
import com.rohit.nyvra.common.persistence.RecordSource;

/** Unit tests for {@link NetWorthCalculator}: no Spring context and no database. */
class NetWorthCalculatorTest {

    /** The day staleness is judged against. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);

    /** Owner shared by all fixtures. */
    private static final UUID USER = UUID.randomUUID();

    /**
     * Builds an active account.
     *
     * @param type    the account type
     * @param balance the balance as a decimal string
     * @return the account
     */
    private static FinancialAccount account(AccountType type, String balance) {
        return new FinancialAccount(USER, type, "Bank", null, type.name(), Money.inr(balance), Instant.now(),
            RecordSource.MANUAL);
    }

    /**
     * Builds a manual entry.
     *
     * @param kind    asset or liability
     * @param cls     the class
     * @param value   the value as a decimal string
     * @param asOf    the valuation day
     * @param cadence days a value stays fresh, or {@code null}
     * @return the entry
     */
    private static ManualAssetLiability manual(ManualItemKind kind, ManualItemClass cls, String value,
                                               LocalDate asOf, Integer cadence) {
        return new ManualAssetLiability(USER, kind, cls, null, Money.inr(value), asOf,
            cadence == null ? null : Duration.ofDays(cadence));
    }

    /** An empty user has zero everywhere and three empty sources. */
    @Test
    void emptyInputsGiveZero() {
        NetWorthComputation c = NetWorthCalculator.calculate(List.of(), List.of(), Money.zero(Money.INR), TODAY);

        assertThat(c.netWorth()).isEqualTo(Money.inr("0"));
        assertThat(c.lines()).isEmpty();
        assertThat(c.sources()).hasSize(3);
        assertThat(c.staleItemCount()).isZero();
    }

    /** Assets, liabilities and the portfolio combine into the expected totals, lines and sources. */
    @Test
    void combinesAccountsManualItemsAndPortfolio() {
        NetWorthComputation c = NetWorthCalculator.calculate(
            List.of(account(AccountType.SAVINGS, "100000.00"), account(AccountType.TERM_DEPOSIT, "50000.00"),
                account(AccountType.EPF, "200000.00"), account(AccountType.LOAN, "30000.00")),
            List.of(manual(ManualItemKind.ASSET, ManualItemClass.REAL_ESTATE, "5000000.00", TODAY, null),
                manual(ManualItemKind.LIABILITY, ManualItemClass.PERSONAL_LOAN, "20000.00", TODAY, null)),
            Money.inr("150000.00"), TODAY);

        assertThat(c.totalAssets()).isEqualTo(Money.inr("5500000.00"));
        assertThat(c.totalLiabilities()).isEqualTo(Money.inr("50000.00"));
        assertThat(c.netWorth()).isEqualTo(Money.inr("5450000.00"));
        assertThat(c.lines()).extracting(NetWorthComputation.Line::netWorthClass).containsExactly(
            NetWorthClass.CASH, NetWorthClass.DEPOSITS, NetWorthClass.RETIREMENT, NetWorthClass.INVESTMENTS,
            NetWorthClass.REAL_ESTATE, NetWorthClass.LOAN, NetWorthClass.PERSONAL_LOAN);
        NetWorthComputation.SourceTotal portfolio = c.sources().get(1);
        assertThat(portfolio.source()).isEqualTo(NetWorthSource.PORTFOLIO);
        assertThat(portfolio.assets()).isEqualTo(Money.inr("150000.00"));
        assertThat(portfolio.itemCount()).isEqualTo(1);
    }

    /** Closed accounts are ignored entirely. */
    @Test
    void closedAccountsAreExcluded() {
        FinancialAccount closed = account(AccountType.SAVINGS, "999.00");
        closed.changeStatus(AccountStatus.CLOSED);

        NetWorthComputation c = NetWorthCalculator.calculate(List.of(closed), List.of(), Money.zero(Money.INR), TODAY);

        assertThat(c.totalAssets()).isEqualTo(Money.inr("0"));
        assertThat(c.sources().get(0).itemCount()).isZero();
    }

    /** A loan or card balance counts as a liability by magnitude, whichever sign it was stored with. */
    @Test
    void liabilityAccountsCountByMagnitude() {
        NetWorthComputation c = NetWorthCalculator.calculate(
            List.of(account(AccountType.LOAN, "-40000.00"), account(AccountType.CREDIT_CARD, "5000.00")),
            List.of(), Money.zero(Money.INR), TODAY);

        assertThat(c.totalLiabilities()).isEqualTo(Money.inr("45000.00"));
        assertThat(c.netWorth()).isEqualTo(Money.inr("-45000.00"));
    }

    /** Manual entries go stale once their cadence has elapsed; stale accounts are counted too. */
    @Test
    void countsStaleItems() {
        FinancialAccount stale = account(AccountType.SAVINGS, "1.00");
        stale.changeStatus(AccountStatus.STALE);

        NetWorthComputation c = NetWorthCalculator.calculate(
            List.of(stale),
            List.of(manual(ManualItemKind.ASSET, ManualItemClass.VEHICLE, "100.00", TODAY.minusDays(100), 90),
                manual(ManualItemKind.ASSET, ManualItemClass.VEHICLE, "100.00", TODAY.minusDays(90), 90),
                manual(ManualItemKind.ASSET, ManualItemClass.OTHER, "100.00", TODAY.minusDays(1000), null)),
            Money.zero(Money.INR), TODAY);

        // the 100-day-old entry and the STALE account; the 90-day one is exactly on the boundary, still fresh
        assertThat(c.staleItemCount()).isEqualTo(2);
    }

    /** Manual classes map to the right side and bucket. */
    @Test
    void manualClassMapping() {
        assertThat(NetWorthClass.of(ManualItemKind.ASSET, ManualItemClass.OTHER)).isEqualTo(NetWorthClass.OTHER_ASSET);
        assertThat(NetWorthClass.of(ManualItemKind.LIABILITY, ManualItemClass.OTHER))
            .isEqualTo(NetWorthClass.OTHER_LIABILITY);
        assertThat(NetWorthClass.of(ManualItemKind.LIABILITY, ManualItemClass.PERSONAL_LOAN).isLiability()).isTrue();
        assertThat(NetWorthClass.of(ManualItemKind.ASSET, ManualItemClass.JEWELLERY).isLiability()).isFalse();
    }
}
