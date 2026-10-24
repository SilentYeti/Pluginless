package com.silentyeti.pluginless.pda;

import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;

import java.util.ArrayList;
import java.util.List;

import static com.silentyeti.pluginless.pda.PdaWidgets.at;
import static com.silentyeti.pluginless.pda.PdaWidgets.button;
import static com.silentyeti.pluginless.pda.PdaWidgets.chip;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.row;

/** A whole-number quantity (shares, items): {@code -} count {@code +} and preset chips that set it. */
final class CountBar {
    private final String subject;
    private final long[] presets;
    private final Runnable changed;
    long count = 1;

    CountBar(String subject, Runnable changed, long... presets) {
        this.subject = subject;
        this.changed = changed;
        this.presets = presets;
    }

    <S> void signals(Subject<S> on) {
        Signals.text(on, "count", 12, s -> "X" + count);
        for (int i = 0; i < presets.length; i++) {
            long value = presets[i];
            Signals.bool(on, "preset" + i, s -> count == value);
        }
    }

    private void set(long value) {
        count = Math.max(1, Math.min(1_000_000, value));
        changed.run();
    }

    List<WidgetDef> widgets(float left, float y) {
        var tree = new ArrayList<WidgetDef>();
        tree.add(at(left, y, button("-", () -> set(count - 1))));
        tree.add(at(left + 16, y + 3, text("{" + subject + ".count}", PdaShell.ACCENT)));
        tree.add(at(left + 52, y, button("+", () -> set(count + 1))));
        var controls = new ArrayList<WidgetDef>();
        for (int i = 0; i < presets.length; i++) {
            long value = presets[i];
            String label = value >= 1000 ? value / 1000 + "K" : Long.toString(value);
            controls.add(chip(label, subject + ".preset" + i, () -> set(value)));
        }
        tree.add(at(left + 70, y, row(2, controls.toArray(WidgetDef[]::new))));
        return tree;
    }
}
