package com.silentyeti.pluginless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final Accounts accounts = new Accounts(100, () -> {});

    @Test
    void newAccountStartsAtStartBalance() {
        assertEquals(100, accounts.balance(a));
    }

    @Test
    void transferMovesMoneyAndConservesTotal() {
        assertTrue(accounts.transfer(a, b, 40));
        assertEquals(60, accounts.balance(a));
        assertEquals(140, accounts.balance(b));
    }

    @Test
    void rejectsOverdraftSelfPayAndNonPositiveAmounts() {
        assertFalse(accounts.transfer(a, b, 101));
        assertFalse(accounts.transfer(a, a, 10));
        assertFalse(accounts.transfer(a, b, 0));
        assertFalse(accounts.transfer(a, b, -5));
        assertFalse(accounts.set(a, -1));
        assertEquals(100, accounts.balance(a));
        assertEquals(100, accounts.balance(b));
    }

    @Test
    void rejectsOverflowWithoutTakingFromSender() {
        accounts.set(b, Long.MAX_VALUE);
        assertFalse(accounts.transfer(a, b, 10));
        assertFalse(accounts.deposit(b, 1));
        assertEquals(100, accounts.balance(a));
    }

    @Test
    void openCreatesTheAccountOnceAndNeverResetsIt() {
        assertFalse(accounts.exists(a));
        accounts.open(a);
        assertTrue(accounts.exists(a));
        accounts.withdraw(a, 30);
        accounts.open(a);
        assertEquals(70, accounts.balance(a));
    }

    @Test
    void topIsRichestFirst() {
        accounts.set(a, 5);
        accounts.set(b, 500);
        assertEquals(b, accounts.top(1).get(0).getKey());
    }
}
