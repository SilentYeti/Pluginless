package dev.silentyeti.pdaharness;

import com.google.gson.JsonElement;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.*;
import com.wf.wflib.ui.widget.Text;
import com.wf.wflib.pda.client.PdaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.regex.Pattern;

/** Observes real subjects and the loaded font; catches loss before and after template evaluation. */
final class TextAudit {
    private static final Subject<String> GUARD = Subject.register("pdaharness_text_guard", String.class, () -> "CAPACITY OVERFLOW");
    static { Signals.text(GUARD, "value", 4, value -> value); }
    private static boolean verified;
    private int fittedDraws;
    private static final Pattern TOKEN = Pattern.compile("\\{([a-zA-Z0-9_.]+)(?:\\|[^{}]+)?}");
    private final Set<String> errors = new LinkedHashSet<>();
    private final List<Map<String, Object>> labels = new ArrayList<>();
    private final List<String> originals = new ArrayList<>();
    private final List<Map<String, Object>> clipped = new ArrayList<>();
    private final List<Map<String, Object>> disclosures = new ArrayList<>();

    static Map<String, Object> check(WidgetDef root, JsonElement snapshot) throws ReflectiveOperationException {
        var audit = new TextAudit();
        audit.strings(snapshot);
        var layout = new UiLayout(ResourceLocation.parse("pdaharness:text-audit"), audit.visit(root)).absolute().instantiate();
        var mc = Minecraft.getInstance();
        var canvas = Canvas.shared();
        Class<?> fittedAudit = null;
        try {
            fittedAudit = Class.forName("com.silentyeti.pluginless.pda.PdaTextAudit");
            fittedAudit.getMethod("reset").invoke(null);
        } catch (ClassNotFoundException baselineJar) { /* Baseline jars only have WFLib Text. */ }
        canvas.begin(new GuiGraphics(mc, mc.renderBuffers().bufferSource()));
        canvas.pushClip(0, 0, 0, 0);
        try {
            layout.render(canvas, 0, 0, 384, 222, 0);
            if (fittedAudit != null) {
                audit.fitted(fittedAudit, canvas);
                audit.disclose(fittedAudit, layout, canvas);
            }
        }
        finally { canvas.popClip(); canvas.end(); }
        if (audit.labels.isEmpty()) audit.errors.add("Text audit inspected no labels");
        return Map.of("labels", audit.labels, "errors", audit.errors, "marked_clips", audit.clipped,
                "disclosures", audit.disclosures, "labels_checked", audit.labels.size());
    }

    private void strings(JsonElement value) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(e -> strings(e.getValue()));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(this::strings);
        else if (value.getAsJsonPrimitive().isString() && value.getAsString().length() >= 12)
            originals.add(value.getAsString());
    }

    // A single terminal period replacing a source suffix is the old character-count shortening convention.
    static boolean shortened(String source, String displayed) {
        String full = source.toLowerCase(Locale.ROOT);
        String shown = displayed.toLowerCase(Locale.ROOT);
        if (!shown.endsWith(".") || shown.endsWith("..") || shown.equals(full)) return false;
        int max = Math.min(full.length() - 1, shown.length() - 1);
        for (int keep = max; keep >= 8; keep--)
            if (shown.endsWith(full.substring(0, keep) + ".")) return true;
        return false;
    }

    static void verifyGuard() throws ReflectiveOperationException {
        if (verified) return;
        if (!shortened("Swings hard and keeps going", "YETI  Swings hard and keeps g.")
                || shortened("Swings hard and keeps going", "Swings hard and keeps going")
                || shortened("A complete sentence.", "A complete sentence.")
                || shortened("Swings hard and keeps going", "Swings hard…"))
            throw new IllegalStateException("Text shortening guard failed");
        var capacity = check(new Text("{pdaharness_text_guard.value}", "#ffffffff", false, "1"), null);
        if (((Set<?>) capacity.get("errors")).stream().noneMatch(e -> e.toString().startsWith("Signal capacity")))
            throw new IllegalStateException("Text guard missed a real signal capacity overflow");
        var fitted = check(new Text("WIDE TEXT", "#ffffffff", false, "1", "14"), null);
        if (((Set<?>) fitted.get("errors")).stream().noneMatch(e -> e.toString().startsWith("Text clipped")))
            throw new IllegalStateException("Text guard missed rendered buffer clipping");
        verified = true;
    }

    private WidgetDef visit(WidgetDef def) throws ReflectiveOperationException {
        if (def == null) return null;
        if (def instanceof Text text) return monitor(text, text.text(), true);
        if (def.getClass().getName().equals("com.silentyeti.pluginless.pda.PdaText")) {
            var accessor = def.getClass().getDeclaredMethod("template");
            if (!accessor.trySetAccessible()) throw new IllegalAccessException("PdaText.template");
            return monitor(def, (String) accessor.invoke(def), false);
        }
        if (!def.getClass().isRecord() || !def.getClass().getPackageName().equals("com.wf.wflib.ui.widget")) return def;
        var fields = def.getClass().getRecordComponents();
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

    private record Read(Signal.Text<Object> signal, SubjectRef<Object> subject) {}

    @SuppressWarnings("unchecked")
    private WidgetDef monitor(WidgetDef text, String expression, boolean wflib) {
        return binder -> {
            var template = binder.text(expression);
            var raw = template.newBuffer();
            var reads = new ArrayList<Read>();
            var matcher = TOKEN.matcher(expression);
            while (matcher.find()) {
                var signal = Signals.byName(matcher.group(1));
                if (signal instanceof Signal.Text<?> s) {
                    var typed = (Signal.Text<Object>) s;
                    reads.add(new Read(typed, binder.subject(typed.subject())));
                }
            }
            Widget node = binder.child(text);
            return new Widget() {
                public void measure(UiEval ev) { node.measure(ev); width = node.width(); height = node.height(); }
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    String value = template.write(ev, raw).toString();
                    if (!wflib && !value.isEmpty()) fittedDraws++;
                    for (var read : reads) {
                        Object subject = read.subject.get(ev);
                        CharSequence full = subject == null ? "" : read.signal.getter().apply(subject);
                        if (full != null && full.length() > read.signal.maxLength())
                            errors.add("Signal capacity " + read.signal.fullName() + "=" + read.signal.maxLength()
                                    + " cannot hold " + full.length() + " characters: " + full);
                    }
                    for (var original : originals) if (shortened(original, value))
                        errors.add("Character shortening: " + original + " -> " + value);
                    String displayed = value;
                    try {
                        if (wflib) {
                            var field = node.getClass().getDeclaredField("buffer");
                            if (!field.trySetAccessible()) throw new IllegalAccessException("buffer");
                            displayed = field.get(node).toString();
                            if (!displayed.equals(value)) errors.add("Text clipped without full-text disclosure: " + value + " -> " + displayed);
                        }
                    } catch (ReflectiveOperationException e) {
                        errors.add("Cannot inspect rendered Text buffer: " + e);
                    }
                    if (wflib && !value.isEmpty()) {
                        labels.add(Map.of("template", expression, "source", value, "displayed", displayed,
                                "x", x, "y", y, "width", width, "height", height));
                        if (x < -0.05f || x + width > 384.05f || width > w + 0.05f || height > h + 0.05f)
                            errors.add("Text exceeds allocated bounds: " + value + " at " + x + "," + y + " size " + width + "x" + height + " allocation " + w + "x" + h);
                    }
                    node.draw(ev, x, y, w, h);
                }
            };
        };
    }

    private void fitted(Class<?> api, Canvas canvas) throws ReflectiveOperationException {
        var entries = (List<?>) api.getMethod("drawn").invoke(null);
        if (fittedDraws > 0 && entries.isEmpty()) errors.add("Fitted-text diagnostics returned no entries; check client audit property");
        for (var entry : entries) {
            var fields = new LinkedHashMap<String, Object>();
            for (var part : entry.getClass().getRecordComponents())
                fields.put(part.getName(), part.getAccessor().invoke(entry));
            String source = (String) fields.get("source");
            float x = ((Number) fields.get("x")).floatValue();
            float limit = ((Number) fields.get("limit")).floatValue();
            for (var line : (List<?>) fields.get("lines")) {
                float width = canvas.width(line.toString());
                if (width > limit + 0.05f || x + width > 384.05f)
                    errors.add("Fitted text exceeds available width: " + line + " width=" + width + " limit=" + limit);
            }
            for (var original : originals) if (shortened(original, source))
                errors.add("Character shortening: " + original + " -> " + source);
            labels.add(fields);
            if (Boolean.TRUE.equals(fields.get("clipped"))) {
                var lines = (List<?>) fields.get("lines");
                String last = lines.isEmpty() ? "" : lines.getLast().toString();
                if (!last.endsWith("...") && !last.endsWith("…"))
                    errors.add("Fitted text lost content without overflow marker: " + source);
                clipped.add(fields);
            }
        }
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }

    private void disclose(Class<?> api, UiInstance layout, Canvas canvas) throws ReflectiveOperationException {
        float oldX = PdaClient.cursorX(), oldY = PdaClient.cursorY();
        int firstLabels = labels.size();
        try {
            for (var entry : clipped) {
                float x = ((Number) entry.get("x")).floatValue() + 1;
                float y = ((Number) entry.get("y")).floatValue() + 1;
                PdaClient.steer((x - PdaClient.cursorX()) / 0.55, (y - PdaClient.cursorY()) / 0.55);
                api.getMethod("reset").invoke(null);
                layout.render(canvas, 0, 0, 384, 222, 0);
                String source = (String) entry.get("source");
                boolean found = false;
                for (var tip : (List<?>) api.getMethod("tips").invoke(null)) {
                    String full = (String) tip.getClass().getMethod("full").invoke(tip);
                    var lines = (List<?>) tip.getClass().getMethod("lines").invoke(tip);
                    if (!full.equals(source)) continue;
                    found = true;
                    String shown = String.join("", lines.stream().map(Object::toString).toList());
                    disclosures.add(Map.of("source", source, "lines", lines));
                    if (!compact(full).equals(compact(shown))) errors.add("Tooltip still truncates full text: " + full + " -> " + shown);
                    for (var line : lines) if (canvas.width(line.toString()) > 222.05f)
                        errors.add("Tooltip line exceeds its box: " + line);
                }
                if (!found) errors.add("No full-text tooltip on hover: " + source);
            }
        } finally {
            PdaClient.steer((oldX - PdaClient.cursorX()) / 0.55, (oldY - PdaClient.cursorY()) / 0.55);
            labels.subList(firstLabels, labels.size()).clear();
            api.getMethod("reset").invoke(null);
        }
    }
}
