package com.silentyeti.pluginless;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Parses configured text without Minecraft; each span carries RGB and legacy decoration flags. */
public final class Formatting {
    public record Span(String text, Integer color, int flags) {}
    private static final int[] COLORS = {0x000000, 0x0000aa, 0x00aa00, 0x00aaaa, 0xaa0000, 0xaa00aa,
            0xffaa00, 0xaaaaaa, 0x555555, 0x5555ff, 0x55ff55, 0x55ffff, 0xff5555, 0xff55ff, 0xffff55, 0xffffff};
    private static final Pattern GRADIENT = Pattern.compile("<gradient:#([0-9a-fA-F]{6}):#([0-9a-fA-F]{6})>(.*?)</gradient>", Pattern.DOTALL);

    public static List<Span> parse(String text) {
        var result = new ArrayList<Span>();
        var matcher = GRADIENT.matcher(text);
        int start = 0;
        Integer color = null;
        int flags = 0;
        while (matcher.find()) {
            var before = legacy(text.substring(start, matcher.start()), color, flags);
            result.addAll(before);
            if (!before.isEmpty()) {
                var last = before.get(before.size() - 1);
                color = last.color();
                flags = last.flags();
            }
            var inside = legacy(matcher.group(3), color, flags);
            int length = inside.stream().mapToInt(s -> s.text().codePointCount(0, s.text().length())).sum();
            int from = Integer.parseInt(matcher.group(1), 16);
            int to = Integer.parseInt(matcher.group(2), 16);
            int index = 0;
            for (var span : inside) {
                for (var cp : span.text().codePoints().toArray()) {
                    double part = length <= 1 ? 0 : (double) index++ / (length - 1);
                    int rgb = 0;
                    for (int shift : new int[]{16, 8, 0}) {
                        int a = (from >> shift) & 255;
                        int b = (to >> shift) & 255;
                        rgb |= (int) Math.round(a + (b - a) * part) << shift;
                    }
                    result.add(new Span(new String(Character.toChars(cp)), rgb, span.flags()));
                }
            }
            start = matcher.end();
        }
        result.addAll(legacy(text.substring(start), color, flags));
        return result;
    }

    private static List<Span> legacy(String text, Integer color, int flags) {
        var spans = new ArrayList<Span>();
        var part = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                int palette = "0123456789abcdef".indexOf(code);
                int decoration = "klmno".indexOf(code);
                boolean hex = code == '#' && i + 8 <= text.length()
                        && text.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}");
                if (palette >= 0 || decoration >= 0 || code == 'r' || hex) {
                    spans.add(new Span(part.toString(), color, flags));
                    part.setLength(0);
                    if (hex || palette >= 0 || code == 'r') {
                        color = hex ? Integer.valueOf(text.substring(i + 2, i + 8), 16) : palette >= 0 ? (Integer) COLORS[palette] : null;
                        flags = 0;
                    } else flags |= 1 << decoration;
                    i += hex ? 7 : 1;
                    continue;
                }
            }
            part.append(c);
        }
        spans.add(new Span(part.toString(), color, flags));
        return spans;
    }

    /** A colour setting may be a code, a hex colour, or a gradient opening tag. */
    public static String colorize(String setting, String text) {
        if (setting == null || setting.isEmpty()) return text;
        if (setting.toLowerCase(Locale.ROOT).startsWith("<gradient:")) return setting + text + "</gradient>";
        return (setting.startsWith("#") ? "&" + setting : setting) + text;
    }

    private Formatting() {}
}
