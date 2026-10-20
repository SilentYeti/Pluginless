package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarginBookTest {
    @Test
    void theLimitIsTheShareOfHoldingsThatKeepsLeverageAtTheMultiple() {
        assertEquals(500_00, MarginBook.maxLoan(1000_00, 2));
        assertEquals(750_00, MarginBook.maxLoan(1000_00, 4));
        assertEquals(0, MarginBook.maxLoan(1000_00, 1));
        assertEquals(0, MarginBook.maxLoan(0, 2));
        assertEquals(0, MarginBook.maxLoan(-5, 2));
        assertEquals(0, MarginBook.maxLoan(1000_00, Double.NaN));
        assertEquals(333, MarginBook.maxLoan(1000, 1.5));
    }

    @Test
    void repeatedBorrowingConvergesOnTheMultipleOfOwnMoney() {
        long own = 1000_00, holdings = own, loan = 0;
        for (int i = 0; i < 60; i++) { loan = MarginBook.maxLoan(holdings, 2); holdings = own + loan; }
        assertTrue(holdings <= 2 * own && holdings > 2 * own - 100, "holdings " + holdings);
    }

    @Test
    void healthWarnsThenLiquidatesAtTheConfiguredRatios() {
        assertEquals(MarginBook.Health.OK, MarginBook.health(0, 0, 1.25, 1.1));
        assertEquals(MarginBook.Health.OK, MarginBook.health(200, 100, 1.25, 1.1));
        assertEquals(MarginBook.Health.OK, MarginBook.health(125, 100, 1.25, 1.1));
        assertEquals(MarginBook.Health.WARN, MarginBook.health(124, 100, 1.25, 1.1));
        assertEquals(MarginBook.Health.WARN, MarginBook.health(110, 100, 1.25, 1.1));
        assertEquals(MarginBook.Health.LIQUIDATE, MarginBook.health(109, 100, 1.25, 1.1));
        assertEquals(MarginBook.Health.LIQUIDATE, MarginBook.health(0, 100, 1.25, 1.1));
    }

    @Test
    void proceedsRepayTheLoanAndLeaveASurplusOrAShortfall() {
        assertEquals(0, MarginBook.shortfall(100, 150));
        assertEquals(50, MarginBook.surplus(100, 150));
        assertEquals(30, MarginBook.shortfall(100, 70));
        assertEquals(0, MarginBook.surplus(100, 70));
        assertEquals(0, MarginBook.shortfall(100, 100));
        assertEquals(0, MarginBook.surplus(100, 100));
    }

    @Test
    void marginInterestUsesTheSharedDailyRule() {
        var loan = new Debts.Debt(1000_00, 1000_00, 0);
        assertEquals(1010_00, Debts.accrue(loan, Debts.DAY, 1).amount());
        assertEquals(1000_00, Debts.accrue(loan, Debts.DAY - 1, 1).amount());
    }
}
