package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuctionBookTest {
    private final UUID seller = UUID.randomUUID();
    private final UUID buyer = UUID.randomUUID();
    private final Accounts accounts = new Accounts(new HashMap<>(), 100, () -> {});
    private final AuctionBook book = new AuctionBook();

    private long listed() {
        return book.list(seller, "ITEM", "minecraft:diamond_pickaxe", "Diamond Pickaxe", 1, 50, 0, 1000).id();
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
        assertFalse(book.cancel(seller, id, false));
        assertTrue(book.expire(5000).isEmpty());
        assertNull(book.boxes.get(seller));
        assertEquals("ITEM", book.take(buyer));
        assertNull(book.take(buyer));
        assertEquals(50, accounts.balance(buyer));
    }

    @Test
    void expiryAndCancelReturnTheItemToTheSellerOnly() {
        long expiring = listed();
        long cancelled = listed();
        assertFalse(book.cancel(buyer, cancelled, false));
        assertTrue(book.cancel(seller, cancelled, false));
        assertEquals("gone", book.buy(buyer, expiring, 1000, 20, accounts, 0));
        assertEquals(1, book.expire(1000).size());
        assertTrue(book.expire(1000).isEmpty());
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
