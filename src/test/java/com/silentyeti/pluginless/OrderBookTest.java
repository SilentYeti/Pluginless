package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {
    private final UUID ann = UUID.randomUUID(), ben = UUID.randomUUID(), cat = UUID.randomUUID();
    private final Accounts accounts = new Accounts(new HashMap<>(), 1000, () -> {});
    private final OrderBook book = new OrderBook();
    private final OrderBook.Fee tenth = (seller, proceeds) -> proceeds / 10;

    private long total() {
        return accounts.balance(ann) + accounts.balance(ben) + accounts.balance(cat) + accounts.balance(Accounts.SERVER) + accounts.balance(Accounts.ESCROW);
    }

    @Test
    void aBuyOrderEscrowsMoneyAndCancelReturnsIt() {
        assertTrue(book.place(ann, "iron", true, 10, 5, 0, accounts, tenth));
        assertEquals(950, accounts.balance(ann));
        assertEquals(50, accounts.balance(Accounts.ESCROW));
        assertFalse(book.cancel(ben, 1, accounts));
        assertTrue(book.cancel(ann, 1, accounts));
        assertFalse(book.cancel(ann, 1, accounts));
        assertEquals(1000, accounts.balance(ann));
        assertFalse(book.place(ann, "iron", true, 10, 101, 0, accounts, tenth));
        assertEquals(3000, total());
    }

    @Test
    void ordersMatchAtTheWaitingPriceBestFirst() {
        book.place(ann, "iron", false, 12, 5, 0, accounts, tenth);
        book.place(ben, "iron", false, 10, 5, 1, accounts, tenth);
        assertEquals(12, book.quote(cat, "iron", true, 8));
        assertEquals(-1, book.quote(cat, "iron", true, 11));
        assertTrue(book.place(cat, "iron", true, 12, 8, 2, accounts, tenth));
        // 5 at 10 from ben, 3 at 12 from ann; cat escrowed 96 and gets 10 back.
        assertEquals(1000 - 50 - 36, accounts.balance(cat));
        assertEquals(1045, accounts.balance(ben));
        assertEquals(1000 + 36 - 3, accounts.balance(ann));
        assertEquals(8, accounts.balance(Accounts.SERVER));
        assertEquals(0, accounts.balance(Accounts.ESCROW));
        assertEquals(Map.of("iron", 8L), book.goods.get(cat));
        assertEquals(2, book.side("iron", false).get(0).remaining());
        assertEquals(3000, total());
    }

    @Test
    void aSellOrderFillsWaitingBuyersAndRestsTheRemainder() {
        book.place(ann, "iron", true, 10, 4, 0, accounts, tenth);
        assertTrue(book.place(ben, "iron", false, 9, 6, 1, accounts, tenth));
        assertEquals(1036, accounts.balance(ben));
        assertEquals(Map.of("iron", 4L), book.goods.get(ann));
        assertEquals(2, book.side("iron", false).get(0).remaining());
        assertTrue(book.cancel(ben, 2, accounts));
        assertEquals(2, book.take(ben, "iron", 64));
        assertEquals(0, book.take(ben, "iron", 64));
        assertEquals(3, book.take(ann, "iron", 3));
        assertEquals(1, book.take(ann, "iron", 64));
        assertEquals(3000, total());
    }

    @Test
    void nobodyTradesWithThemselves() {
        book.place(ann, "iron", false, 10, 5, 0, accounts, tenth);
        book.place(ann, "iron", true, 10, 5, 1, accounts, tenth);
        assertEquals(2, book.count(ann));
        assertNull(book.goods.get(ann));
        assertEquals(-1, book.quote(ann, "iron", true, 1));
    }

    @Test
    void sparkScalesBetweenLowAndHigh() {
        assertEquals("▁█▄", OrderBook.spark(List.of(1L, 9L, 5L), 10));
        assertEquals("▄▄", OrderBook.spark(List.of(5L, 5L), 10));
        assertEquals("▄", OrderBook.spark(List.of(1L, 9L), 1));
        assertEquals("", OrderBook.spark(List.of(), 10));
    }
}
