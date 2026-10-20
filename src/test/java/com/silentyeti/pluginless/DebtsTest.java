package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebtsTest {
    @Test
    void interestUsesFullDaysUnpaidPrincipalAndPreservesPartialDays() {
        var original = new Debts.Debt(10_000, 10_000, 1000);
        assertEquals(original, Debts.accrue(original, Debts.DAY, 1));
        var accrued = Debts.accrue(original, 1000 + 2 * Debts.DAY + 1234, 1);
        assertEquals(new Debts.Debt(10_200, 10_000, 1000 + 2 * Debts.DAY), accrued);
        assertEquals(accrued, Debts.accrue(accrued, 1000 + 2 * Debts.DAY + 1234, 1));
        assertEquals(new Debts.Debt(10_300, 10_000, 1000 + 3 * Debts.DAY), Debts.accrue(accrued, 1000 + 3 * Debts.DAY, 1));
        assertEquals(new Debts.Debt(10_000, 10_000, 1000 + Debts.DAY), Debts.accrue(original, 1000 + Debts.DAY, 0));
    }

    @Test
    void repaymentPaysInterestFirstAndNeverCreatesNegativeDebt() {
        var debt = new Debts.Debt(10_200, 10_000, 1000);
        assertEquals(new Debts.Debt(10_100, 10_000, 1000), Debts.repay(debt, 100));
        assertEquals(new Debts.Debt(9000, 9000, 1000), Debts.repay(debt, 1200));
        assertEquals(0, Debts.repay(debt, 10_200).amount());
        assertThrows(IllegalArgumentException.class, () -> Debts.repay(debt, 0));
        assertThrows(IllegalArgumentException.class, () -> Debts.repay(debt, 10_201));
        assertThrows(IllegalArgumentException.class, () -> new Debts.Debt(1, 2, 0));
    }

    @Test
    void offlineCatchupMatchesDailyRoundingAndOverflowIsBounded() {
        var tiny = new Debts.Debt(1, 1, 0);
        assertEquals(Debts.accrue(Debts.accrue(tiny, Debts.DAY, 1), 2 * Debts.DAY, 1), Debts.accrue(tiny, 2 * Debts.DAY, 1));
        assertEquals(Long.MAX_VALUE, Debts.accrue(new Debts.Debt(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, 0), 2 * Debts.DAY, 100).amount());
        assertThrows(IllegalArgumentException.class, () -> Debts.accrue(tiny, Debts.DAY, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Debts.accrue(tiny, Debts.DAY, -1));
    }
}
