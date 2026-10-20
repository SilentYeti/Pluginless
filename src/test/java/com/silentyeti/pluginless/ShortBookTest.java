package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShortBookTest {
    @Test
    void theDepositIsAShareOfThePositionAndNeverZero() {
        assertEquals(500_00, ShortBook.requiredDeposit(1000_00, 0.5));
        assertEquals(1, ShortBook.requiredDeposit(1, 0.1));
        assertEquals(110, ShortBook.requiredDeposit(100, 1.1));
    }

    @Test
    void aFallingPriceIsProfitAndARisingOneIsLoss() {
        assertEquals(300, ShortBook.pnl(1000, 700));
        assertEquals(-300, ShortBook.pnl(1000, 1300));
    }

    @Test
    void thePositionClosesWhenLossesReachTheCloseShareOfTheDeposit() {
        assertFalse(ShortBook.shouldClose(500, 200, 0.9));
        assertFalse(ShortBook.shouldClose(500, -449, 0.9));
        assertTrue(ShortBook.shouldClose(500, -450, 0.9));
        assertTrue(ShortBook.shouldClose(500, -900, 0.9));
        assertTrue(ShortBook.shouldClose(0, 100, 0.9));
    }

    @Test
    void theFeeIsProRataOfTheDailyRate() {
        assertEquals(5, ShortBook.fee(1000, 0.5, 86_400_000));
        assertEquals(0, ShortBook.fee(1000, 0.5, 3_600_000));
        assertEquals(0, ShortBook.fee(1000, 0, 86_400_000));
        assertEquals(0, ShortBook.fee(1000, 0.5, -5));
        assertEquals(10, ShortBook.fee(1000, 0.5, 2 * 86_400_000L));
    }

    @Test
    void settlingSplitsTheDepositAndOwesWhatItCannotCover() {
        assertEquals(new ShortBook.Settlement(500, 0, 300, 0), ShortBook.settle(500, 300));
        assertEquals(new ShortBook.Settlement(300, 200, 0, 0), ShortBook.settle(500, -200));
        assertEquals(new ShortBook.Settlement(0, 500, 0, 100), ShortBook.settle(500, -600));
        assertEquals(new ShortBook.Settlement(500, 0, 0, 0), ShortBook.settle(500, 0));
    }
}
