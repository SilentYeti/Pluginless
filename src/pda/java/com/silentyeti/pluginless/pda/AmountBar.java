package com.silentyeti.pluginless.pda;

import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static com.silentyeti.pluginless.pda.PdaWidgets.at;
import static com.silentyeti.pluginless.pda.PdaWidgets.button;
import static com.silentyeti.pluginless.pda.PdaWidgets.chip;
import static com.wf.wflib.ui.Ui.text;

/**
 * The money amount a page works with: {@code -} amount {@code +}, step chips, shortcut buttons (BAL, MIN...) and TYPE.
 * The page registers its signals with {@link #signals} (on its own subject) and places {@link #widgets}.
 */
final class AmountBar {
    /** A button that sets the amount to a value only the page knows, e.g. the balance or the minimum bid. */
    record Shortcut(String label, float width, LongSupplier value) {}

    private final String subject;
    private final long[] steps;
    private final Supplier<JsonObject> view;
    long amount;
    private long step;

    AmountBar(String subject, Supplier<JsonObject> view, long amount, long... steps) {
        this.subject = subject;
        this.view = view;
        this.amount = amount;
        this.steps = steps;
        this.step = steps[Math.min(1, steps.length - 1)];
    }

    <S> void signals(Subject<S> on) {
        Signals.text(on, "amount", 20, s -> PdaClientState.money(view.get(), amount));
        for (int i = 0; i < steps.length; i++) {
            long value = steps[i];
            Signals.bool(on, "step" + i, s -> step == value);
        }
    }

    void adjust(int direction) { amount = Math.max(0, amount + direction * step); }

    void type() {
        PdaWidgets.prompt("Amount", "", typed -> {
            long cents = PdaWidgets.cents(typed);
            if (cents >= 0) amount = cents;
        });
    }

    /** One row at {@code y}: stepper, chips, shortcuts, TYPE. */
    List<WidgetDef> widgets(float y, Shortcut... shortcuts) {
        var tree = new ArrayList<WidgetDef>();
        tree.add(at(8, y, button("-", () -> adjust(-1))));
        tree.add(at(26, y + 3, text("{" + subject + ".amount}", PdaShell.ACCENT)));
        tree.add(at(72, y, button("+", () -> adjust(1))));
        float x = 92;
        for (int i = 0; i < steps.length; i++) {
            long value = steps[i];
            String label = value >= 100_000 ? "$" + value / 100_000 + "K" : "$" + value / 100;
            tree.add(at(x, y, chip(label, subject + ".step" + i, () -> step = value)));
            x += 10 + label.length() * 4.3f;
        }
        x += 2;
        for (var shortcut : shortcuts) {
            tree.add(at(x, y, button(shortcut.label(), () -> amount = Math.max(0, shortcut.value().getAsLong()))));
            x += shortcut.width();
        }
        tree.add(at(x, y, button("TYPE", this::type)));
        return tree;
    }
}
