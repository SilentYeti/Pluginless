package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaxesTest {
    @Test
    void percentRoundsAndNeverGoesNegative() {
        assertEquals(5, Taxes.percent(250, 2, 1));
        assertEquals(3, Taxes.percent(250, 2, 0.5));
        assertEquals(0, Taxes.percent(250, 2, -1));
        assertEquals(0, Taxes.percent(250, 0, 1));
    }

    @Test
    void highestReachedTierWins() {
        var tiers = Map.of("0", 1.0, "100", 2.0, "1000", 2.5);
        assertEquals(1.0, Taxes.tier(tiers, 99_99));
        assertEquals(2.0, Taxes.tier(tiers, 100_00));
        assertEquals(2.5, Taxes.tier(tiers, 5000_00));
        assertEquals(0, Taxes.tier(Map.of("10", 3.0), 5_00));
    }

    @Test
    void streakContinuesOnlyOnConsecutiveDays() {
        var first = new Taxes.Streak(0, 0).next(10);
        assertEquals(new Taxes.Streak(10, 1), first);
        assertNull(first.next(10));
        assertEquals(new Taxes.Streak(11, 2), first.next(11));
        assertEquals(new Taxes.Streak(13, 1), first.next(13));
    }

    @Test
    void hourlyCapResetsAfterAnHour() {
        var cap = new Taxes.HourlyCap();
        var player = UUID.randomUUID();
        assertTrue(cap.allow(player, 0, 2));
        assertTrue(cap.allow(player, 1, 2));
        assertFalse(cap.allow(player, 2, 2));
        assertTrue(cap.allow(UUID.randomUUID(), 2, 2));
        assertTrue(cap.allow(player, 3_600_000, 2));
    }
}
