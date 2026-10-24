package com.silentyeti.pluginless.pda;

import com.google.gson.JsonArray;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.function.Supplier;

/**
 * A line chart of unit prices, oldest left to newest right, scaled between the lowest and highest shown: connected
 * segments with a dot on the latest price, faint guides at both extremes, and both extremes labelled. With one price
 * only the dot is drawn.
 */
record PriceChart(float w, float h, Supplier<JsonArray> prices, Supplier<String> high, Supplier<String> low) implements WidgetDef {
    private static final int LINE = 0xFFA9D46C;
    private static final int BACK = 0xFF2C2F2C;
    private static final int GUIDE = 0xFF454A45;
    private static final int DIM = 0xFF8D9488;
    private static final float PAD = 5;
    private static final float WIDTH = 1.2f;

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
                // Plot area: below the labels, inside a small margin.
                float left = x + PAD;
                float right = x + w - PAD;
                float top = y + 12;
                float bottom = y + h - PAD;
                ev.canvas.fill(left, top, right, top + 1, GUIDE);
                ev.canvas.fill(left, bottom - 1, right, bottom, GUIDE);
                int n = data.size();
                float lastX = 0;
                float lastY = 0;
                for (int i = 0; i < n; i++) {
                    float px = n == 1 ? (left + right) / 2 : left + (right - left) * i / (n - 1);
                    float py = hi == lo ? (top + bottom) / 2 : bottom - (bottom - top) * (data.get(i).getAsLong() - lo) / (hi - lo);
                    if (i > 0) {
                        ev.canvas.line(lastX, lastY, px, py, WIDTH, LINE);
                    }
                    lastX = px;
                    lastY = py;
                }
                ev.canvas.disc(lastX, lastY, 2f, LINE);
                ev.canvas.text("HI " + high.get(), x + 3, y + 2, DIM, false);
                ev.canvas.text("LO " + low.get(), x + w / 2, y + 2, DIM, false);
            }
        };
    }
}
