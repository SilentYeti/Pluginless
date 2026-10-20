package com.silentyeti.pluginless;

import java.util.regex.Pattern;

public final class Durations {
    private static final Pattern DURATION = Pattern.compile("([0-9]+)([smhdw])");

    public static long millis(String input) {
        var match = DURATION.matcher(input);
        if (!match.matches()) throw new IllegalArgumentException("expected a positive duration such as 30d");
        long unit = switch (match.group(2)) {
            case "s" -> 1000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            default -> 604_800_000L;
        };
        try {
            long result = Math.multiplyExact(Long.parseLong(match.group(1)), unit);
            if (result <= 0) throw new IllegalArgumentException("duration must be positive");
            return result;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("duration is too large", e);
        }
    }

    private Durations() {}
}
