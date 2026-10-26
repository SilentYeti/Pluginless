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
    private static final float WIDTH = 230;
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
                List<String> lines = TextFit.layout(canvas, full, WIDTH - 8, 12);
                float widest = 0;
                for (String line : lines) {
                    widest = Math.max(widest, canvas.width(line));
                }
                float bw = widest + 8;
                float bh = lines.size() * canvas.lineHeight() + 6;
                // Keep it inside the page: below the text, or above it when there is no room.
                float bx = Math.max(4, Math.min(at[0], 380 - bw));
                float by = at[1] + 12 + bh > 236 ? at[1] - bh - 2 : at[1] + 12;
                canvas.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, EDGE);
                canvas.fill(bx, by, bx + bw, by + bh, BACK);
                for (int i = 0; i < lines.size(); i++) {
                    canvas.text(lines.get(i), bx + 4, by + 3 + i * canvas.lineHeight(), TEXT, false);
                }
                if (PdaTextAudit.enabled()) {
                    PdaTextAudit.tip(full, lines);
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
