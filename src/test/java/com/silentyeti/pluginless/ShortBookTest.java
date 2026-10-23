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

    private final java.util.UUID player = java.util.UUID.randomUUID();

    private java.util.Map<java.util.UUID, Long> funded(long escrow) {
        var balances = new java.util.HashMap<java.util.UUID, Long>();
        balances.put(Accounts.ESCROW, escrow);
        return balances;
    }

    @Test
    void aLossBeyondTheDepositSplitsTheDepositAndRecordsTheDebtOnce() {
        var balances = funded(500);
        var accounts = new Accounts(balances, 100, () -> {});
        var owed = new long[1];
        assertTrue(ShortBook.pay(accounts, player, ShortBook.settle(500, -800), cents -> false, cents -> { owed[0] += cents; return true; }));
        assertEquals(300, owed[0]);
        assertEquals(500, accounts.balance(Accounts.SERVER));
        assertEquals(0, accounts.balance(Accounts.ESCROW));
    }

    @Test
    void aFailedDebtRecordPutsTheEscrowBack() {
        var balances = funded(500);
        var accounts = new Accounts(balances, 100, () -> {});
        assertFalse(ShortBook.pay(accounts, player, ShortBook.settle(500, -800), cents -> false, cents -> false));
        assertEquals(500, accounts.balance(Accounts.ESCROW));
        assertEquals(0, accounts.balance(Accounts.SERVER));
        assertEquals(100, accounts.balance(player));
    }

    @Test
    void aFailedProfitPayoutPutsTheDepositBack() {
        var balances = funded(500);
        var accounts = new Accounts(balances, 100, () -> {});
        assertFalse(ShortBook.pay(accounts, player, ShortBook.settle(500, 200), cents -> false, cents -> true));
        assertEquals(500, accounts.balance(Accounts.ESCROW));
        assertEquals(100, accounts.balance(player));
        var paid = new long[1];
        assertTrue(ShortBook.pay(accounts, player, ShortBook.settle(500, 200), cents -> { paid[0] = cents; return true; }, cents -> true));
        assertEquals(200, paid[0]);
        assertEquals(600, accounts.balance(player));
    }

    @Test
    void anOverflowingServerOrPlayerBalanceUndoesTheReleaseBeforeIt() {
        var balances = funded(500);
        balances.put(player, Long.MAX_VALUE - 100);
        var accounts = new Accounts(balances, 100, () -> {});
        assertFalse(ShortBook.pay(accounts, player, ShortBook.settle(500, -200), cents -> true, cents -> true));
        assertEquals(500, accounts.balance(Accounts.ESCROW));
        assertEquals(0, accounts.balance(Accounts.SERVER));
        balances.put(player, 100L);
        balances.put(Accounts.SERVER, Long.MAX_VALUE - 10);
        assertFalse(ShortBook.pay(accounts, player, ShortBook.settle(500, -200), cents -> true, cents -> true));
        assertEquals(500, accounts.balance(Accounts.ESCROW));
        assertEquals(100, accounts.balance(player));
    }
}
