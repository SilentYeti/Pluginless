package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CompaniesIntentTest {
    @Test void onlyPlayerCommandsWithExactTokenCountsCanBeBuilt() {
        assertEquals("company hire Acme Alex manager", CompaniesIntent.command("hire", List.of("Acme", "Alex", "manager")));
        assertEquals("shop buy Corner minecraft:bread 16", CompaniesIntent.command("shop_buy", List.of("Corner", "minecraft:bread", "16")));
        assertEquals("shop offer Corner minecraft:bread 2.50 1 64", CompaniesIntent.command("shop_offer", List.of("Corner", "minecraft:bread", "2.50", "1", "64")));
        for (var verb : List.of("admin", "shop_admin", "op", "execute", "create anything"))
            assertNull(CompaniesIntent.command(verb, List.of("Alex")));
        for (var token : List.of("@a", "Alex manager", "Alex\nkill", "\"Alex\"", "", "a".repeat(65)))
            assertNull(CompaniesIntent.command("fire", List.of("Acme", token)));
        assertNull(CompaniesIntent.command("withdraw", List.of("Acme")));
        assertNull(CompaniesIntent.command("withdraw", List.of("Acme", "1", "extra")));
    }
}
