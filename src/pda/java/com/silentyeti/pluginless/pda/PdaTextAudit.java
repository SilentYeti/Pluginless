package com.silentyeti.pluginless.pda;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the fitted text widgets drew, for tests: the full source string, the lines actually shown and whether the
 * last line was cut with an ellipsis. Recording is off unless the system property {@code pluginless.pda.textaudit}
 * is set, so normal play pays nothing. Entries are keyed by template and position, so the list stays small.
 */
public final class PdaTextAudit {
    /** {@code clipped}: the shown lines do not hold the whole source (an ellipsis ends the last line); the full text then appears
     * in a tooltip while the PDA cursor rests on it (see {@link #tips}). */
    public record Entry(String template, String source, List<String> lines, boolean clipped, float limit, float x, float y,
                        float naturalWidth) {}

    private static final boolean ON = System.getProperty("pluginless.pda.textaudit") != null;
    private static final Map<String, Entry> DRAWN = new LinkedHashMap<>();
    private static final List<Tip> TIPS = new ArrayList<>();

    /** A full-text tooltip that was drawn: the whole source, the lines it was laid out in, and its box on the 384x240 PDA
     * surface (always inside it). */
    public record Tip(String full, List<String> lines, float x, float y, float width, float height) {}

    private PdaTextAudit() {}

    static boolean enabled() { return ON; }

    static synchronized void record(Entry entry) {
        DRAWN.put(entry.template() + "@" + Math.round(entry.x()) + "," + Math.round(entry.y()), entry);
    }

    /** Everything drawn since the last {@link #reset}, in first-drawn order. */
    public static synchronized List<Entry> drawn() { return new ArrayList<>(DRAWN.values()); }

    public static synchronized void reset() { DRAWN.clear(); TIPS.clear(); }

    static synchronized void tip(String full, List<String> lines, float x, float y, float width, float height) {
        TIPS.add(new Tip(full, List.copyOf(lines), x, y, width, height));
    }

    /** Tooltips drawn since the last {@link #reset}; a clipped text under the cursor must appear here with its full source. */
    public static synchronized List<Tip> tips() { return new ArrayList<>(TIPS); }
}
