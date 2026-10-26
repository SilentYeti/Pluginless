package com.silentyeti.pluginless.pda;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.pda.client.PdaClient;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a clipped text shows in full: when the PDA cursor rests on a text that ended in "...", the whole string appears
 * in a box over everything else. Texts queue themselves while drawing; this widget is the last thing the app draws.
 */
record TextTip() implements WidgetDef {
    private static final float WIDTH = 340;
    /** As many lines as the page has room for; a text longer than that ends in "..." inside the box. */
    private static final int MAX_LINES = 24;
    private static final float PAGE_W = 384;
    private static final float PAGE_H = 240;
    private static final int BACK = 0xF0202220;
    private static final int EDGE = 0xFFA9D46C;
    private static final int TEXT = 0xFFD2D6CC;
    private static final List<String> QUEUE = new ArrayList<>();
    private static final List<float[]> AT = new ArrayList<>();

    /** Called by a clipped text that is under the cursor. */
    static synchronized void show(String full, float x, float y) {
        QUEUE.clear();
        AT.clear();
        QUEUE.add(full);
        AT.add(new float[]{x, y});
    }

    @Override
    public Widget create(Binder binder) {
        return new Widget() {
            @Override
            public void measure(UiEval ev) {
                width = 0;
                height = 0;
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                String full;
                float[] at;
                synchronized (TextTip.class) {
                    if (QUEUE.isEmpty()) {
                        return;
                    }
                    full = QUEUE.get(0);
                    at = AT.get(0);
                    QUEUE.clear();
                    AT.clear();
                }
                Canvas canvas = ev.canvas;
                // The whole box stays on the page (0..PAGE_W x 0..PAGE_H, 4 margin); lines are capped to what fits.
                int fits = (int) Math.max(1, Math.floor((PAGE_H - 8 - 6) / (float) canvas.lineHeight()));
                List<String> lines = TextFit.layout(canvas, full, WIDTH - 8, Math.min(MAX_LINES, fits));
                float widest = 0;
                for (String line : lines) {
                    widest = Math.max(widest, canvas.width(line));
                }
                float bw = widest + 8;
                float bh = lines.size() * canvas.lineHeight() + 6;
                float bx = Math.max(4, Math.min(at[0], PAGE_W - 4 - bw));
                float by = at[1] + 12;
                if (by + bh > PAGE_H - 4) {
                    by = at[1] - bh - 2;
                }
                by = Math.max(4, Math.min(by, PAGE_H - 4 - bh));
                canvas.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, EDGE);
                canvas.fill(bx, by, bx + bw, by + bh, BACK);
                for (int i = 0; i < lines.size(); i++) {
                    canvas.text(lines.get(i), bx + 4, by + 3 + i * canvas.lineHeight(), TEXT, false);
                }
                if (PdaTextAudit.enabled()) {
                    PdaTextAudit.tip(full, lines, bx, by, bw, bh);
                }
            }
        };
    }

    static boolean over(float x, float y, float w, float h) {
        float cx = PdaClient.cursorX();
        float cy = PdaClient.cursorY();
        return cx >= x && cx <= x + w && cy >= y && cy <= y + h;
    }
}
