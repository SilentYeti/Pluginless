package com.silentyeti.pluginless;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoticesTest {
    private final UUID player = UUID.randomUUID();

    @AfterEach
    void clear() { Notices.clear(); }

    @Test
    void newestFirstAndCountedUnread() {
        Notices.push(player, "bank", "first", 1);
        Notices.push(player, "bank", "second", 2);
        assertEquals("second", Notices.list(player, 5).get(0).text());
        assertEquals(2, Notices.unread(player));
    }

    @Test
    void markReadKeepsHistory() {
        Notices.push(player, "auction", "sold", 1);
        Notices.markRead(player);
        assertEquals(0, Notices.unread(player));
        assertEquals(1, Notices.list(player, 5).size());
    }

    @Test
    void historyIsBoundedAndUnreadStaysInRange() {
        for (int i = 0; i < Notices.LIMIT + 10; i++) Notices.push(player, "bank", "n" + i, i);
        assertEquals(Notices.LIMIT, Notices.list(player, 100).size());
        assertEquals(Notices.LIMIT, Notices.unread(player));
    }

    @Test
    void strangersHaveNothing() {
        assertEquals(0, Notices.unread(UUID.randomUUID()));
        assertEquals(0, Notices.list(UUID.randomUUID(), 5).size());
    }
}
