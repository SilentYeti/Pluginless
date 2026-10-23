package com.silentyeti.pluginless.pda;

import com.google.gson.JsonArray;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.function.Supplier;

/** A column chart of unit prices, oldest left; scaled between the lowest and highest shown, with both labelled. */
record PriceChart(float w, float h, Supplier<JsonArray> prices, Supplier<String> high, Supplier<String> low) implements WidgetDef {
    private static final int BAR = 0xFFA9D46C;
    private static final int BACK = 0xFF2C2F2C;
    private static final int DIM = 0xFF8D9488;

    @Override
    public Widget create(Binder binder) {
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = w;
                height = h;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                ev.canvas.fill(x, y, x + w, y + h, BACK);
                var data = prices.get();
                if (data.isEmpty()) {
                    ev.canvas.text("NO TRADES YET", x + 4, y + 4, DIM, false);
                    return;
                }
                long lo = Long.MAX_VALUE;
                long hi = Long.MIN_VALUE;
                for (var price : data) {
                    lo = Math.min(lo, price.getAsLong());
                    hi = Math.max(hi, price.getAsLong());
                }
                float column = w / Math.max(data.size(), 12);
                float top = y + 10;
                float span = h - 12;
                for (int i = 0; i < data.size(); i++) {
                    long price = data.get(i).getAsLong();
                    float height = hi == lo ? span / 2 : 2 + (span - 2) * (price - lo) / (hi - lo);
                    float left = x + i * column;
                    ev.canvas.fill(left, y + h - height, left + Math.max(1, column - 1), y + h, BAR);
                }
                ev.canvas.text("HI " + high.get(), x + 3, y + 1, DIM, false);
                ev.canvas.text("LO " + low.get(), x + w / 2, y + 1, DIM, false);
            }
        };
    }
}
