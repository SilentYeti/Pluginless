package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShareBookTest {
    private final UUID owner = UUID.randomUUID(), raider = UUID.randomUUID(), saver = UUID.randomUUID();
    private final Accounts accounts = new Accounts(new HashMap<>(), 10_000, () -> {});
    private final CompanyBook companies = new CompanyBook();
    private final ShareBook shares = new ShareBook();
    private final OrderBook.Fee free = (seller, proceeds) -> 0;
    private final CompanyBook.Company acme = companies.create(owner, "Acme", 0, 1, accounts);
    private final UUID account = CompanyBook.account(acme.id);

    private boolean order(UUID player, boolean buy, long price, long quantity) {
        return shares.order(player, acme, buy, price, quantity, 0, accounts, free, 0, 0);
    }

    @Test
    void issuingSellsForTheCompanyAndOnlyTheOwnerCanIssue() {
        assertFalse(shares.issue(raider, acme, 100, 40, 10, 0, accounts, free));
        assertTrue(shares.issue(owner, acme, 100, 40, 10, 0, accounts, free));
        assertEquals(40, shares.held(acme.id, owner));
        assertEquals(40, shares.outstanding(acme.id));
        assertTrue(order(saver, true, 10, 30));
        assertEquals(30, shares.held(acme.id, saver));
        assertEquals(300, accounts.balance(account));
        assertEquals(9_700, accounts.balance(saver));
        assertEquals(70, shares.outstanding(acme.id));
    }

    @Test
    void aMajorityTakesTheCompanyAndAMinorityCannot() {
        shares.issue(owner, acme, 100, 40, 10, 0, accounts, free);
        order(raider, true, 10, 40);
        assertFalse(shares.takeover(raider, acme));
        order(raider, true, 10, 1);
        assertTrue(shares.takeover(raider, acme));
        assertEquals(raider, acme.owner);
        assertFalse(acme.can(owner, "spend"));
        assertFalse(shares.takeover(raider, acme));
    }

    @Test
    void sharesInASellOrderStillVoteAndCancelReturnsThem() {
        shares.issue(owner, acme, 100, 100, 10, 0, accounts, free);
        assertFalse(order(owner, false, 10, 101));
        assertTrue(order(owner, false, 50, 60));
        assertEquals(40, shares.held(acme.id, owner));
        assertEquals(100, shares.votes(acme.id).get(owner));
        long id = shares.market.side(ShareBook.asset(acme.id), false).get(0).id();
        assertFalse(shares.cancel(raider, id, accounts));
        assertTrue(shares.cancel(owner, id, accounts));
        assertEquals(100, shares.held(acme.id, owner));
    }

    @Test
    void dividendsArePaidByVotesFromWhateverIsInTheAccount() {
        shares.issue(owner, acme, 100, 60, 10, 0, accounts, free);
        order(saver, true, 10, 40);
        assertEquals(-1, shares.dividend(saver, acme, 100, accounts));
        assertEquals(-1, shares.dividend(owner, acme, 9_999, accounts));
        // The only money in the account is what the investor just paid in: a Ponzi payout works.
        assertEquals(400, shares.dividend(owner, acme, 400, accounts));
        assertEquals(10_240, accounts.balance(owner));
        assertEquals(10_000 - 400 + 160, accounts.balance(saver));
        assertEquals(0, accounts.balance(account));
    }

    @Test
    void optionalProtectionsLimitTheFounderAndThePrice() {
        shares.issue(owner, acme, 100, 100, 10, 0, accounts, free);
        assertFalse(shares.order(owner, acme, false, 10, 60, 0, accounts, free, 51, 0));
        assertTrue(shares.order(owner, acme, false, 10, 40, 0, accounts, free, 51, 0));
        assertTrue(order(saver, true, 10, 10));
        assertFalse(shares.order(saver, acme, true, 20, 1, 0, accounts, free, 0, 50));
        assertTrue(shares.order(saver, acme, true, 15, 1, 0, accounts, free, 0, 50));
    }

    @Test
    void liquidationRefundsOrdersAndSplitsTheAccount() {
        shares.issue(owner, acme, 100, 50, 10, 0, accounts, free);
        order(saver, true, 10, 50);
        order(raider, true, 5, 10);
        order(saver, false, 99, 20);
        shares.liquidate(acme, accounts);
        assertEquals(10_000, accounts.balance(raider));
        assertEquals(0, accounts.balance(Accounts.ESCROW));
        assertEquals(10_250, accounts.balance(owner));
        assertEquals(10_000 - 500 + 250, accounts.balance(saver));
        assertEquals(0, shares.outstanding(acme.id));
        assertTrue(shares.market.orders.isEmpty());
    }
}
