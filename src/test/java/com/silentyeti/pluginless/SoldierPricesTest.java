package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoldierPricesTest {
    private static final List<String> TABLE = List.of("heavy=3", "mg=1.5", " Sniper = 2 ", "medic=oops", "bad", "=4", "assault=-1");

    @Test
    void aRolePaysItsMultiplierAndUnlistedRolesPayTheBase() {
        assertEquals(1500, SoldierPrices.price(500, "heavy", TABLE));
        assertEquals(750, SoldierPrices.price(500, "mg", TABLE));
        assertEquals(1000, SoldierPrices.price(500, "sniper", TABLE), "case and spaces do not matter");
        assertEquals(500, SoldierPrices.price(500, "rifle", TABLE));
    }

    @Test
    void aBrokenTableEntryCostsTheBasePriceNotTheSystem() {
        assertEquals(500, SoldierPrices.price(500, "medic", TABLE));
        assertEquals(500, SoldierPrices.price(500, "assault", TABLE), "a negative multiplier is ignored");
        assertEquals(0, SoldierPrices.price(0, "heavy", TABLE), "free stays free");
    }

    @Test
    void anEnormousPriceDoesNotOverflow() {
        assertEquals(Integer.MAX_VALUE, SoldierPrices.price(1e9, "heavy", TABLE));
    }
}
