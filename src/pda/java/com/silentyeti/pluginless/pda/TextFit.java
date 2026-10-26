package com.silentyeti.pluginless.pda;

import com.wf.gemrender.ui.Canvas;

import java.util.ArrayList;
import java.util.List;

/** Fits a string to a width with the real font: whole, wrapped at words, or as a last resort ending in "...". */
final class TextFit {
    static final String DOTS = "...";

    private TextFit() {}

    /** One line no wider than {@code limit}; ends in "..." only when the text had to be cut. */
    static String oneLine(Canvas canvas, String text, float limit) {
        if (canvas.width(text) <= limit) {
            return text;
        }
        float room = limit - canvas.width(DOTS);
        int keep = text.length();
        while (keep > 0 && canvas.width(text, 0, keep) > room) {
            keep--;
        }
        return text.substring(0, keep).stripTrailing() + DOTS;
    }

    /** Up to {@code lines} lines: one line if it fits, else word-wrapped, the last line cut with "..." if still too long. */
    static List<String> layout(Canvas canvas, String text, float limit, int lines) {
        if (lines <= 1 || canvas.width(text) <= limit) {
            return List.of(oneLine(canvas, text, limit));
        }
        var out = new ArrayList<String>();
        var line = new StringBuilder();
        for (String word : text.split(" +")) {
            // A word wider than the box on its own breaks inside the word.
            while (canvas.width(word) > limit) {
                int fit = word.length();
                while (fit > 1 && canvas.width(word, 0, fit) > limit) {
                    fit--;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                out.add(word.substring(0, fit));
                word = word.substring(fit);
            }
            String joined = line.length() == 0 ? word : line + " " + word;
            if (canvas.width(joined) <= limit) {
                line.setLength(0);
                line.append(joined);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        if (out.size() <= lines) {
            return out;
        }
        var kept = new ArrayList<>(out.subList(0, lines));
        var rest = new StringBuilder(kept.get(lines - 1));
        for (int i = lines; i < out.size(); i++) {
            rest.append(' ').append(out.get(i));
        }
        kept.set(lines - 1, oneLine(canvas, rest.toString(), limit));
        return kept;
    }
}
