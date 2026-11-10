package com.silentyeti.pluginless;

import java.util.List;
import java.util.Locale;

/** What a WFLib soldier costs: a base price scaled by its role ("heavy=3"). Plain Java, so the arithmetic is unit-tested without Minecraft or WFLib. */
public final class SoldierPrices {
    private SoldierPrices() {}

    /** The multiplier for a role id in a {@code role=multiplier} table; 1 for a role not listed or an entry that does not parse. */
    static double multiplier(String role, List<String> table) {
        for (String entry : table) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || !entry.substring(0, eq).trim().toLowerCase(Locale.ROOT).equals(role.toLowerCase(Locale.ROOT))) continue;
            try {
                double m = Double.parseDouble(entry.substring(eq + 1).trim());
                if (Double.isFinite(m) && m >= 0) return m;
            } catch (NumberFormatException ignored) {
                // a typo in the table costs the base price, not the whole system
            }
        }
        return 1;
    }

    /** Whole currency units: the base times the role's multiplier, rounded. */
    static int price(double base, String role, List<String> table) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(base * multiplier(role, table)));
    }
}
