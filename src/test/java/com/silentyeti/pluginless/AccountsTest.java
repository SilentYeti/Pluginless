package com.silentyeti.pluginless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final Accounts accounts = new Accounts(new HashMap<>(), 100, () -> {});

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

    @Test
    void everyMovementIsLoggedOnceAndFailuresHaveNoMovement() {
        var movements = new ArrayList<Accounts.Movement>();
        var ledger = new Accounts(new HashMap<>(), 100, () -> {}, movements::add);
        ledger.open(a);
        ledger.open(b);
        movements.clear();
        assertTrue(ledger.transfer(a, b, 40));
        assertEquals(List.of(new Accounts.Movement(a, b, 40, "transfer")), movements);
        assertFalse(ledger.transfer(a, b, 100));
        assertEquals(1, movements.size());
        assertTrue(ledger.deposit(a, 5));
        assertTrue(ledger.withdraw(a, 5));
        assertTrue(ledger.set(a, 80));
        assertEquals(4, movements.size());
        assertEquals(20, movements.get(3).amount());
    }

    @Test
    void serverStartsEmptyCollectsAndPaysWithoutEnteringBaltop() {
        assertEquals(0, accounts.balance(Accounts.SERVER));
        assertTrue(accounts.transfer(a, Accounts.SERVER, 40));
        assertTrue(accounts.transfer(Accounts.SERVER, b, 20));
        assertEquals(20, accounts.balance(Accounts.SERVER));
        assertFalse(accounts.transfer(Accounts.SERVER, b, 21));
        assertTrue(accounts.top(10).stream().noneMatch(e -> e.getKey().equals(Accounts.SERVER)));
    }

    @Test
    void provisionalAccountsMoveWithoutAddingAnotherStartingBalance() {
        accounts.deposit(a, 10);
        assertTrue(accounts.move(a, b));
        assertEquals(110, accounts.balance(b));
        assertFalse(accounts.exists(a));
        accounts.set(a, 5);
        accounts.set(b, Long.MAX_VALUE);
        assertFalse(accounts.move(a, b));
        assertEquals(5, accounts.balance(a));
        assertEquals(Long.MAX_VALUE, accounts.balance(b));
    }
    @Test
    void incomeRepaysDebtBeforeSpendingAndConservesTransfers() {
        var balances = new HashMap<UUID, Long>();
        var movements = new ArrayList<Accounts.Movement>();
        long[] debt = {50};
        var ledger = new Accounts(balances, 0, () -> {}, movements::add, new Accounts.Income() {
            public long withheld(UUID player, long cents) { return player.equals(b) ? Math.min(debt[0], cents) : 0; }
            public void repaid(UUID player, long cents) { debt[0] -= cents; }
        });
        ledger.set(a, 100);
        assertTrue(ledger.transfer(a, b, 70));
        assertEquals(30, ledger.balance(a)); assertEquals(20, ledger.balance(b));
        assertEquals(50, ledger.balance(Accounts.SERVER)); assertEquals(0, debt[0]);
        assertEquals(new Accounts.Movement(b, Accounts.SERVER, 50, "debt.income"), movements.get(movements.size() - 1));
        debt[0] = 30;
        assertTrue(ledger.deposit(b, 20)); assertEquals(20, ledger.balance(b)); assertEquals(10, debt[0]);
        assertTrue(ledger.set(b, 50)); assertEquals(40, ledger.balance(b)); assertEquals(0, debt[0]);
    }

    @Test
    void failedGarnishmentChangesNeitherAccountDebtNorLogAndRefundIsNotIncome() {
        var balances = new HashMap<UUID, Long>();
        var movements = new ArrayList<Accounts.Movement>();
        long[] debt = {50};
        var ledger = new Accounts(balances, 0, () -> {}, movements::add, new Accounts.Income() {
            public long withheld(UUID player, long cents) { return player.equals(b) ? Math.min(debt[0], cents) : 0; }
            public void repaid(UUID player, long cents) { debt[0] -= cents; }
        });
        ledger.set(a, 100); ledger.set(Accounts.SERVER, Long.MAX_VALUE);
        int before = movements.size();
        assertFalse(ledger.transfer(a, b, 70)); assertFalse(ledger.deposit(b, 70)); assertFalse(ledger.set(b, 70));
        assertEquals(100, ledger.balance(a)); assertEquals(0, ledger.balance(b)); assertEquals(50, debt[0]);
        assertEquals(before, movements.size());
        assertTrue(ledger.transfer(Accounts.SERVER, b, 70));
        assertEquals(Long.MAX_VALUE - 20, ledger.balance(Accounts.SERVER)); assertEquals(20, ledger.balance(b));
        debt[0] = 50;
        assertTrue(ledger.refund(b, 20)); assertEquals(40, ledger.balance(b)); assertEquals(50, debt[0]);
    }

}
