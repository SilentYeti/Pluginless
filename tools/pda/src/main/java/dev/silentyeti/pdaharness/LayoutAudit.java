package dev.silentyeti.pdaharness;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.*;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/** Measures the real widget tree with the loaded font; no screenshot pixel guesses or fake font widths. */
final class LayoutAudit {
    private final List<String> errors = new ArrayList<>();
    private int checked;

    static void verifyGuard() throws ReflectiveOperationException {
        WidgetDef label = new Text("ALIGNMENT", "#ffffffff", false, "1");
        var bad = new Button(item -> {}, "true", "#00000000", "#00000000", "#00000000", "#ffffffff",
                1, 0, 4, 1, 100, 22, com.wf.wflib.ui.widget.Point.TOP_LEFT, label);
        try { check(bad); }
        catch (IllegalStateException expected) {
            if (expected.getMessage().contains("not vertically centered")) {
                check(new Button(bad.action(), bad.enabled(), bad.background(), bad.hover(), bad.pressed(), bad.border(),
                        1, 0, 4, 1, 100, 22, com.wf.wflib.ui.widget.Point.CENTER, label));
                return;
            }
            throw expected;
        }
        throw new IllegalStateException("Layout guard failed to detect deliberately misaligned text");
    }

    static int check(WidgetDef root) throws ReflectiveOperationException {
        var audit = new LayoutAudit();
        var layout = new UiLayout(ResourceLocation.parse("pdaharness:layout-audit"), audit.visit(root)).absolute().instantiate();
        var mc = Minecraft.getInstance();
        var canvas = Canvas.shared();
        canvas.begin(new GuiGraphics(mc, mc.renderBuffers().bufferSource()));
        // Exercise measurement and drawing, but do not change the captured framebuffer.
        canvas.pushClip(0, 0, 0, 0);
        try { layout.render(canvas, 0, 0, 384, 222, 0); }
        finally { canvas.popClip(); canvas.end(); }
        if (audit.checked == 0) throw new IllegalStateException("Layout audit checked no button labels");
        if (!audit.errors.isEmpty()) throw new IllegalStateException(String.join("; ", audit.errors));
        return audit.checked;
    }

    private WidgetDef visit(WidgetDef def) throws ReflectiveOperationException {
        if (def == null) return null;
        if (def instanceof Button button) return monitor(button);
        // Custom chart/preview definitions are opaque renderers, not widget containers.
        if (!def.getClass().isRecord() || !def.getClass().getPackageName().equals("com.wf.wflib.ui.widget")) return def;
        RecordComponent[] fields = def.getClass().getRecordComponents();
        var types = new Class<?>[fields.length];
        var args = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            types[i] = fields[i].getType();
            Object value = fields[i].getAccessor().invoke(def);
            if (value instanceof WidgetDef child) value = visit(child);
            else if (value instanceof List<?> list) {
                var children = new ArrayList<Object>();
                for (var item : list) children.add(item instanceof WidgetDef child ? visit(child) : item);
                value = children;
            }
            args[i] = value;
        }
        return (WidgetDef) def.getClass().getDeclaredConstructor(types).newInstance(args);
    }

    private WidgetDef monitor(Button button) throws ReflectiveOperationException {
        var child = visit(button.child());
        boolean text = button.child() instanceof Text;
        return binder -> {
            float[] box = new float[4];
            WidgetDef label = child == null ? null : childBinder -> {
                Widget node = childBinder.child(child);
                return new Widget() {
                    public void measure(UiEval ev) { node.measure(ev); width = node.width(); height = node.height(); }
                    public void draw(UiEval ev, float x, float y, float w, float h) {
                        if (width > 0 && height > 0) {
                            checked++;
                            float edge = button.borderWidth(), tolerance = 0.05f;
                            if (x < box[0] + edge - tolerance || y < box[1] + edge - tolerance
                                    || x + width > box[0] + box[2] - edge + tolerance
                                    || y + height > box[1] + box[3] - edge + tolerance)
                                errors.add("Label touches/exceeds button border: " + button.child());
                            if (text && Math.abs(y + height / 2 - box[1] - box[3] / 2) > tolerance)
                                errors.add("Label is not vertically centered: " + button.child());
                        }
                        node.draw(ev, x, y, w, h);
                    }
                };
            };
            Widget node = binder.child(new Button(button.action(), button.enabled(), button.background(), button.hover(),
                    button.pressed(), button.border(), button.borderWidth(), button.radius(), button.padX(), button.padY(),
                    button.minWidth(), button.minHeight(), button.align(), label));
            return new Widget() {
                public void measure(UiEval ev) { node.measure(ev); width = node.width(); height = node.height(); }
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    box[0] = x; box[1] = y; box[2] = width; box[3] = height;
                    node.draw(ev, x, y, w, h);
                }
            };
        };
    }
}
