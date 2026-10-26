package com.silentyeti.pluginless.pda;

import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiAction;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.when;

/** Small pieces every page uses, so the pages look alike. Coordinates are layout units inside the page area. */
public final class PdaWidgets {
    /** Capacity of a text signal: far above any real string, so the signal never cuts text; fitting is done at draw time. */
    public static final int TEXT_MAX = 1200;

    private PdaWidgets() {}

    /** Text to the page edge from where it is placed; ellipsis only if it would run past. */
    public static WidgetDef text(String template, String color) {
        return new PdaText(template, color, 0, 1);
    }

    /** One line in a box {@code width} units wide. */
    public static WidgetDef fit(String template, String color, float width) {
        return new PdaText(template, color, width, 1);
    }

    /** Up to {@code lines} lines in a box {@code width} wide, wrapped at words. */
    public static WidgetDef wrap(String template, String color, float width, int lines) {
        return new PdaText(template, color, width, lines);
    }

    public static WidgetDef button(String label, Runnable run) {
        return button(label, "true", run);
    }

    /** {@code enabled}: a signal expression such as {@code pda_pl_bank.can_pay}. */
    public static WidgetDef button(String label, String enabled, Runnable run) {
        return new Button(item -> run.run(), enabled, PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 4, 1, 0, 13, Point.LEFT, text(label, PdaShell.TEXT));
    }

    /** A button drawn with the accent while {@code selected} (a signal expression) holds. */
    public static WidgetDef chip(String label, String selected, Runnable run) {
        UiAction action = item -> run.run();
        return when(selected,
                new Button(action, "true", PdaShell.PRESS, PdaShell.HOVER, PdaShell.PRESS, PdaShell.ACCENT,
                        1, 0, 4, 1, 0, 13, Point.LEFT, text(label, PdaShell.ACCENT)),
                new Button(action, "true", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                        1, 0, 4, 1, 0, 13, Point.LEFT, text(label, PdaShell.TEXT)));
    }

    /** A full-width list row button; {@code label} is a template over the row's item signals. */
    public static WidgetDef row(String template, float width, String selected, UiAction action) {
        return when(selected,
                new Button(action, "true", PdaShell.PRESS, PdaShell.HOVER, PdaShell.PRESS, PdaShell.ACCENT,
                        1, 0, 4, 1, width, 11, Point.LEFT, fit(template, PdaShell.ACCENT, width - 8)),
                new Button(action, "true", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.PANEL,
                        1, 0, 4, 1, width, 11, Point.LEFT, fit(template, PdaShell.TEXT, width - 8)));
    }

    /** A bordered panel with a dim title; callers place their own content with absolute coordinates. */
    public static WidgetDef card(float x, float y, float w, float h, String title) {
        return stack(at(x, y, PdaShell.panel(w, h)), at(x + 5, y + 4, text(title, PdaShell.DIM)));
    }

    public static WidgetDef at(float x, float y, WidgetDef child) {
        return com.wf.wflib.ui.Ui.at(Float.toString(x), Float.toString(y), child);
    }

    /** One-line typed entry (WFLib's prompt, PDA stays raised); {@code done} gets the text. */
    public static void prompt(String title, String initial, Consumer<String> done) {
        com.wf.wflib.pda.client.TextPromptScreen.open(Component.literal(title), 24, initial, done);
    }

    /** Typed money like {@code 12.5} to cents; -1 when it is not a plain amount. */
    public static long cents(String typed) {
        try {
            var value = new java.math.BigDecimal(typed.trim().replace(",", ""));
            if (value.signum() < 0 || value.compareTo(new java.math.BigDecimal("10000000000000")) > 0) return -1;
            return value.movePointRight(2).setScale(0, java.math.RoundingMode.DOWN).longValueExact();
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
