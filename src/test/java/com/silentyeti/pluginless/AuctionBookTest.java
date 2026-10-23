package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuctionBookTest {
    private final UUID seller = UUID.randomUUID();
    private final UUID buyer = UUID.randomUUID();
    private final UUID rival = UUID.randomUUID();
    private final Accounts accounts = new Accounts(new HashMap<>(), 100, () -> {});
    private final AuctionBook book = new AuctionBook();

    private long listed() {
        return book.list(seller, "ITEM", "minecraft:diamond_pickaxe", "Diamond Pickaxe", 1, 50, 0, 1000).id();
    }

    private List<AuctionBook.Listing> expire(long now) {
        return book.expire(now, accounts, (who, price) -> price / 10);
    }

    private long auction() {
        return book.listAuction(seller, "ITEM", "minecraft:diamond", "Diamond", 1, 50, 0, 1_000_000).id();
    }

    @Test
    void bidsMustBeatTheRaiseAndRefundTheOutbidAtOnce() {
        long id = auction();
        assertEquals("auction", book.buy(buyer, id, 30, 20, accounts, 0));
        assertEquals("own", book.bid(seller, id, 60, 30, 20, 10, accounts));
        assertEquals("grace", book.bid(buyer, id, 60, 5, 20, 10, accounts));
        assertEquals("low", book.bid(buyer, id, 49, 30, 20, 10, accounts));
        assertEquals("funds", book.bid(buyer, id, 101, 30, 20, 10, accounts));
        assertNull(book.bid(buyer, id, 50, 30, 20, 10, accounts));
        assertEquals(50, accounts.balance(Accounts.ESCROW));
        assertEquals("low", book.bid(rival, id, 54, 31, 20, 10, accounts));
        assertNull(book.bid(rival, id, 55, 31, 20, 10, accounts));
        assertEquals(100, accounts.balance(buyer));
        assertEquals(45, accounts.balance(rival));
        assertEquals(55, accounts.balance(Accounts.ESCROW));
        assertEquals("outbid", book.notices.get(buyer).get(0).kind());
    }

    @Test
    void raisingYourOwnBidOnlyEscrowsTheDifference() {
        long id = auction();
        assertNull(book.bid(buyer, id, 50, 30, 20, 10, accounts));
        assertNull(book.bid(buyer, id, 60, 31, 20, 10, accounts));
        assertEquals(40, accounts.balance(buyer));
        assertEquals(60, accounts.balance(Accounts.ESCROW));
    }

    @Test
    void aLateBidExtendsTheEndToTwoMinutes() {
        long id = auction();
        assertNull(book.bid(buyer, id, 50, 100_000, 20, 10, accounts));
        assertEquals(1_000_000, book.listings.get(id).expires());
        assertNull(book.bid(rival, id, 60, 1_000_000 - 119_000, 20, 10, accounts));
        assertEquals(1_000_000 - 119_000 + AuctionBook.EXTENSION, book.listings.get(id).expires());
        assertEquals("gone", book.bid(buyer, id, 70, 1_000_000 - 119_000 + AuctionBook.EXTENSION, 20, 10, accounts));
    }

    @Test
    void anAuctionSettlesOnceFromEscrowOnExpiry() {
        long id = auction();
        assertNull(book.bid(buyer, id, 50, 30, 20, 10, accounts));
        assertTrue(expire(999_999).isEmpty());
        assertEquals(1, expire(1_000_000).size());
        assertEquals(5, accounts.balance(Accounts.SERVER));
        assertEquals(145, accounts.balance(seller));
        assertEquals(0, accounts.balance(Accounts.ESCROW));
        assertEquals(List.of("ITEM"), book.boxes.get(buyer));
        assertNull(book.boxes.get(seller));
        assertTrue(expire(2_000_000).isEmpty());
        assertEquals(50, book.history.get("minecraft:diamond").get(0).unitPrice());
    }

    @Test
    void aSellerPaymentThatOverflowsUndoesTheTaxAndKeepsTheListingForARetry() {
        var balances = new HashMap<UUID, Long>();
        var full = new Accounts(balances, 100, () -> {});
        long id = auction();
        assertNull(book.bid(buyer, id, 50, 30, 20, 10, full));
        balances.put(seller, Long.MAX_VALUE - 10);
        assertTrue(book.expire(1_000_000, full, (who, price) -> price / 10).isEmpty());
        assertTrue(book.listings.containsKey(id));
        assertEquals(50, full.balance(Accounts.ESCROW));
        assertEquals(0, full.balance(Accounts.SERVER));
        assertNull(book.boxes.get(buyer));
        assertNull(book.notices.get(seller));
        assertTrue(book.history.isEmpty());
        balances.put(seller, 100L);
        assertEquals(1, book.expire(1_100_000, full, (who, price) -> price / 10).size());
        assertEquals(5, full.balance(Accounts.SERVER));
        assertEquals(145, full.balance(seller));
        assertEquals(0, full.balance(Accounts.ESCROW));
        assertEquals(List.of("ITEM"), book.boxes.get(buyer));
    }

    @Test
    void aTaxPaymentThatOverflowsKeepsTheListingAndTheEscrow() {
        var balances = new HashMap<UUID, Long>();
        var full = new Accounts(balances, 100, () -> {});
        long id = auction();
        assertNull(book.bid(buyer, id, 50, 30, 20, 10, full));
        balances.put(Accounts.SERVER, Long.MAX_VALUE - 2);
        assertTrue(book.expire(1_000_000, full, (who, price) -> price / 10).isEmpty());
        assertTrue(book.listings.containsKey(id));
        assertEquals(50, full.balance(Accounts.ESCROW));
        assertEquals(100, full.balance(seller));
        assertNull(book.boxes.get(buyer));
        assertTrue(book.history.isEmpty());
    }

    @Test
    void oneStuckAuctionDoesNotBlockTheOthers() {
        var balances = new HashMap<UUID, Long>();
        var full = new Accounts(balances, 100, () -> {});
        var stuckSeller = UUID.randomUUID();
        long stuck = book.listAuction(stuckSeller, "STUCK", "minecraft:gold_ingot", "Gold", 1, 50, 0, 1_000_000).id();
        long fine = auction();
        assertNull(book.bid(buyer, stuck, 50, 30, 20, 10, full));
        assertNull(book.bid(rival, fine, 50, 30, 20, 10, full));
        balances.put(stuckSeller, Long.MAX_VALUE);
        var settled = book.expire(1_000_000, full, (who, price) -> price / 10);
        assertEquals(List.of(fine), settled.stream().map(AuctionBook.Listing::id).toList());
        assertTrue(book.listings.containsKey(stuck));
        assertEquals(50, full.balance(Accounts.ESCROW));
    }

    @Test
    void anAuctionWithoutBidsGoesBackToTheSeller() {
        long id = auction();
        assertEquals(1, expire(1_000_000).size());
        assertEquals(List.of("ITEM"), book.boxes.get(seller));
        assertFalse(book.listings.containsKey(id));
    }

    @Test
    void onlyAnAdminCancelsAnAuctionWithBidsAndTheBidderIsRefunded() {
        long id = auction();
        assertNull(book.bid(buyer, id, 50, 30, 20, 10, accounts));
        assertFalse(book.cancel(seller, id, false, accounts));
        assertTrue(book.cancel(seller, id, true, accounts));
        assertEquals(100, accounts.balance(buyer));
        assertEquals(0, accounts.balance(Accounts.ESCROW));
        assertEquals(List.of("ITEM"), book.boxes.get(seller));
        assertEquals("fixed", book.bid(buyer, listed(), 60, 30, 20, 10, accounts));
    }

    @Test
    void saleMovesMoneyItemAndTaxOnce() {
        long id = listed();
        assertEquals("grace", book.buy(buyer, id, 5, 20, accounts, 5));
        assertEquals("own", book.buy(seller, id, 30, 20, accounts, 5));
        assertNull(book.buy(buyer, id, 30, 20, accounts, 5));
        assertEquals(50, accounts.balance(buyer));
        assertEquals(145, accounts.balance(seller));
        assertEquals(5, accounts.balance(Accounts.SERVER));
        assertEquals(List.of("ITEM"), book.boxes.get(buyer));
        assertEquals(50, book.history.get("minecraft:diamond_pickaxe").get(0).unitPrice());
    }

    @Test
    void anItemIsNeverSoldReturnedOrCollectedTwice() {
        long id = listed();
        assertNull(book.buy(buyer, id, 30, 20, accounts, 0));
        assertEquals("gone", book.buy(buyer, id, 31, 20, accounts, 0));
        assertFalse(book.cancel(seller, id, false, accounts));
        assertTrue(expire(5000).isEmpty());
        assertNull(book.boxes.get(seller));
        assertEquals("ITEM", book.take(buyer));
        assertNull(book.take(buyer));
        assertEquals(50, accounts.balance(buyer));
    }

    @Test
    void expiryAndCancelReturnTheItemToTheSellerOnly() {
        long expiring = listed();
        long cancelled = listed();
        assertFalse(book.cancel(buyer, cancelled, false, accounts));
        assertTrue(book.cancel(seller, cancelled, false, accounts));
        assertEquals("gone", book.buy(buyer, expiring, 1000, 20, accounts, 0));
        assertEquals(1, expire(1000).size());
        assertTrue(expire(1000).isEmpty());
        assertEquals(List.of("ITEM", "ITEM"), book.boxes.get(seller));
        assertEquals(100, accounts.balance(buyer));
    }

    @Test
    void aBuyerWhoCannotPayChangesNothing() {
        long id = book.list(seller, "ITEM", "minecraft:dirt", "Dirt", 64, 500, 0, 1000).id();
        assertEquals("funds", book.buy(buyer, id, 30, 20, accounts, 10));
        assertEquals(100, accounts.balance(buyer));
        assertEquals(0, accounts.balance(Accounts.SERVER));
        assertTrue(book.listings.containsKey(id));
    }

    @Test
    void viewSearchesFiltersAndSorts() {
        listed();
        book.list(seller, "ITEM", "minecraft:bread", "Bread", 3, 10, 5, 1000);
        assertEquals(2, book.view("", null, "newest", 10).size());
        assertEquals("Bread", book.view("", null, "price", 10).get(0).name());
        assertEquals(1, book.view("PICK", null, "newest", 10).size());
        assertEquals(1, book.view("", List.of("bread", "apple"), "newest", 10).size());
        assertTrue(book.view("", null, "newest", 2000).isEmpty());
    }
}
