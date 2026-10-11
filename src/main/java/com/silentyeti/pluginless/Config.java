package com.silentyeti.pluginless;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<String> CURRENCY_SYMBOL = BUILDER
            .comment("Shown in front of every amount")
            .define("currencySymbol", "$");
    private static final ModConfigSpec.DoubleValue START_BALANCE = BUILDER
            .comment("Balance of a player who has never had money")
            .defineInRange("startBalance", 100.0, 0.0, 1e12);

    static final ModConfigSpec SPEC = BUILDER.build();

    static long startBalanceCents() {
        return Math.round(START_BALANCE.get() * 100);
    }

    public static String format(long cents) {
        return "%s%,d.%02d".formatted(CURRENCY_SYMBOL.get(), cents / 100, cents % 100);
    }
}
