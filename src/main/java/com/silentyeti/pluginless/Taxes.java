package com.silentyeti.pluginless;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tax, fee and income arithmetic in cents, without Minecraft. */
public final class Taxes {
    /** A percentage of an amount, scaled by a rank multiplier, rounded half up and never negative. */
    public static long percent(long cents, double percent, double multiplier) {
        double value = cents * percent / 100 * multiplier;
        return value <= 0 || !Double.isFinite(value) ? 0 : Math.round(value);
    }

    /** The rate of the highest tier whose threshold, in currency units, the amount reaches. */
    public static double tier(Map<String, Double> tiers, long cents) {
        double best = -1, rate = 0;
        for (var tier : tiers.entrySet()) {
            double from = Double.parseDouble(tier.getKey());
            if (cents >= from * 100 && from > best) { best = from; rate = tier.getValue(); }
        }
        return rate;
    }

    /** Yesterday continues a streak, today claims nothing, anything older starts again. */
    public record Streak(long day, int count) {
        public Streak next(long today) {
            if (day == today) return null;
            return new Streak(today, day == today - 1 ? count + 1 : 1);
        }
    }

    /** Counts paid events per player in a rolling hour, so a mob farm stops paying. */
    public static final class HourlyCap {
        private final Map<UUID, long[]> windows = new HashMap<>();

        public boolean allow(UUID player, long now, int cap) {
            var window = windows.computeIfAbsent(player, id -> new long[]{now, 0});
            if (now - window[0] >= 3_600_000) { window[0] = now; window[1] = 0; }
            return window[1]++ < cap;
        }
    }

    private Taxes() {}
}
