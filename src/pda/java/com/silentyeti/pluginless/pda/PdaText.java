package com.silentyeti.pluginless.pda;

import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.ColorProp;
import com.wf.wflib.ui.TextProp;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;

import java.util.List;

/**
 * Templated text fitted to the real font and the room it has, never to a character count. One line when it fits;
 * otherwise it wraps onto up to {@code lines} lines at word breaks (a word wider than the box breaks inside the word),
 * and only when even that does not fit does the last line end in "...". {@code box} 0 means "to the page edge from
 * where it is drawn" (single line); a positive width is the box. Nothing is cut silently: the clip is always marked.
 */
record PdaText(String template, String color, float box, int lines) implements WidgetDef {
    /** Right edge of the usable page, layout units (page is 384 wide, 8 margin). */
    static final float EDGE = 376;

    @Override
    public Widget create(Binder binder) {
        TextProp prop = binder.text(template);
        ColorProp colour = binder.color(color);
        return new Widget() {
            private final TextBuffer raw = new TextBuffer(prop.capacity());
            private String source = "";
            private String laidFor = null;
            private float laidLimit = -1;
            private int laidFont = -1;
            private List<String> laid = List.of();
            private float natural;

            @Override
            public void measure(UiEval ev) {
                prop.write(ev, raw);
                source = raw.toString();
                Canvas canvas = ev.canvas;
                natural = source.isEmpty() ? 0 : canvas.width(source);
                if (box > 0) {
                    layout(canvas, box);
                    float widest = 0;
                    for (String line : laid) {
                        widest = Math.max(widest, canvas.width(line));
                    }
                    super.width = widest;
                    super.height = source.isEmpty() ? 0 : (laid.size() - 1) * canvas.lineHeight() + canvas.lineHeight() - 1;
                } else {
                    super.width = natural;
                    super.height = source.isEmpty() ? 0 : canvas.lineHeight() - 1;
                }
            }

            @Override
            public void draw(UiEval ev, float x, float y, float w, float h) {
                if (source.isEmpty()) {
                    return;
                }
                int argb = colour.get(ev);
                if ((argb >>> 24) == 0) {
                    return;
                }
                Canvas canvas = ev.canvas;
                if (box <= 0) {
                    // Auto width: whatever room is left before the page edge.
                    layout(canvas, Math.max(12, EDGE - x));
                }
                for (int i = 0; i < laid.size(); i++) {
                    canvas.text(laid.get(i), x, y + i * canvas.lineHeight(), argb, false);
                }
                boolean clipped = !String.join(" ", laid).equals(source) && !String.join("", laid).replace(" ", "").equals(source.replace(" ", ""));
                float shownWidth = 0;
                for (String line : laid) {
                    shownWidth = Math.max(shownWidth, canvas.width(line));
                }
                // A clipped text shows in full while the cursor rests on it.
                if (clipped && TextTip.over(x, y, shownWidth, laid.size() * canvas.lineHeight())) {
                    TextTip.show(source, x, y);
                }
                if (PdaTextAudit.enabled()) {
                    String shown = String.join(" ", laid);
                    PdaTextAudit.record(new PdaTextAudit.Entry(template, source, List.copyOf(laid),
                            clipped, laidLimit, x, y, natural));
                }
            }

            private void layout(Canvas canvas, float limit) {
                if (source.equals(laidFor) && limit == laidLimit && canvas.fontVersion() == laidFont) {
                    return;
                }
                laidFor = source;
                laidLimit = limit;
                laidFont = canvas.fontVersion();
                laid = TextFit.layout(canvas, source, limit, lines);
            }
        };
    }
}
