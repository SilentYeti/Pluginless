package com.silentyeti.pluginless;

import java.util.Collection;
import java.util.Locale;

public final class CosmeticNames {
    public static boolean valid(String name, int limit, Collection<String> blocked, Collection<String> otherRealNames) {
        if (name == null || name.length() > limit || !name.matches("[a-zA-Z0-9_]+")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return blocked.stream().noneMatch(word -> lower.contains(word.toLowerCase(Locale.ROOT)))
                && otherRealNames.stream().noneMatch(real -> real.equalsIgnoreCase(name));
    }

    public static String color(String input) {
        if (input != null && input.matches("(?i)(?:&[0-9a-f]|#(?:[0-9a-f]{6})|&#(?:[0-9a-f]{6}))")) {
            return input.toLowerCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("expected a colour code or #rrggbb");
    }

    public static String gradient(String from, String to) {
        return "<gradient:" + hex(from) + ":" + hex(to) + ">";
    }

    private static String hex(String color) {
        String normalized = color(color);
        var span = Formatting.parse(Formatting.colorize(normalized, "x")).stream().filter(s -> !s.text().isEmpty()).findFirst().orElseThrow();
        return "#%06x".formatted(span.color());
    }

    private CosmeticNames() {}
}
