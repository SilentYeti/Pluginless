package com.silentyeti.pluginless.pda;

import com.google.gson.JsonObject;
import com.silentyeti.pluginless.CosmeticNames;
import com.silentyeti.pluginless.Formatting;
import com.silentyeti.pluginless.Text;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.pda.client.TextPromptScreen;
import com.wf.wflib.ui.*;
import com.wf.wflib.ui.input.UiAction;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.wf.wflib.ui.Ui.*;
import static com.silentyeti.pluginless.pda.PdaWidgets.text;

/** Drafts are local previews; only a server snapshot or action result confirms a change. */
final class CosmeticsClientPage implements PdaClientPage {
    static final CosmeticsClientPage INSTANCE = new CosmeticsClientPage();
    private static final String[] COLORS = {"#000000", "#0000aa", "#00aa00", "#00aaaa", "#aa0000", "#aa00aa", "#ffaa00", "#aaaaaa",
            "#555555", "#5555ff", "#55ff55", "#55ffff", "#ff5555", "#ff55ff", "#ffff55", "#ffffff"};
    private static final String[] TABS = {"COLOUR", "GRADIENT", "NICKNAME", "TITLE", "SKULL/HAT"};
    private static final String[] PERKS = {"namecolor", "namegradient", "nick", "", "hat"};
    private record Title(String key, String label, String formatted, boolean allowed) {}
    private record Slot(int index, String name, int count) {}
    private static final Subject<Title> TITLE = Subject.register("pda_pl_cos_title", Title.class);
    private static final Subject<Slot> SLOT = Subject.register("pda_pl_cos_slot", Slot.class);
    private int tab;
    private int selectedSlot = -1;
    private String player = "";
    private String nick, color, title;
    private String from = "#ff5555", to = "#5555ff";

    public String id() { return "cosmetics"; }
    public String title() { return "COSMETICS"; }

    private JsonObject view() {
        var view = PdaClientState.view(id());
        String current = PdaClientState.text(view, "player");
        if (!current.equals(player)) {
            player = current;
            nick = color = title = null;
            selectedSlot = -1;
        }
        return view;
    }

    private String value(String key, String draft) { return draft == null ? PdaClientState.text(view(), key) : draft; }
    private boolean allowed(String perk) { return view().has(perk + "_allowed") && view().get(perk + "_allowed").getAsBoolean(); }
    private void send(String verb, String... args) { PdaClientSetup.send(id(), verb, args); }
    private void prompt(String label, int max, String initial, Consumer<String> done) {
        TextPromptScreen.open(Component.literal(label), max, initial, done);
    }

    public void signals() {
        Signals.num(PlApp.APP, "cos_tab", app -> tab);
        Signals.text(PlApp.APP, "cos_lock", PdaWidgets.TEXT_MAX, app -> {
            if (tab == 4) return "SKULL: " + (allowed("skull") ? "unlocked" : "rank/grant required")
                    + " | HAT: " + (allowed("hat") ? "unlocked" : "rank/grant required");
            if (tab == 3) return "Titles unlock through your rank or an individual grant.";
            String perk = PERKS[tab];
            return allowed(perk) ? "Preview changes, then apply." : "LOCKED: requires rank perk or grant: " + perk;
        });
        Signals.text(PlApp.APP, "cos_color", PdaWidgets.TEXT_MAX, app -> value("color", color).isEmpty() ? "RANK DEFAULT" : value("color", color));
        Signals.text(PlApp.APP, "cos_from", PdaWidgets.TEXT_MAX, app -> from);
        Signals.text(PlApp.APP, "cos_to", PdaWidgets.TEXT_MAX, app -> to);
        Signals.text(PlApp.APP, "cos_nick", PdaWidgets.TEXT_MAX, app -> value("nick", nick).isEmpty() ? "USE REAL NAME" : value("nick", nick));
        Signals.text(PlApp.APP, "cos_price", PdaWidgets.TEXT_MAX, app -> "GET MY SKULL  " + PdaClientState.money(view(), PdaClientState.number(view(), "skull_price")));
        Signals.text(PlApp.APP, "cos_hat", PdaWidgets.TEXT_MAX, app -> selectedSlot < 0 ? "Choose an inventory slot to wear as a hat." :
                slots().stream().filter(s -> s.index == selectedSlot).map(s -> "Slot " + (s.index + 1) + ": " + s.name + " x" + s.count).findFirst().orElse("Choose a slot."));
        for (var perk : List.of("namecolor", "namegradient", "nick", "skull", "hat"))
            Signals.bool(PlApp.APP, "cos_" + perk + "_allowed", app -> allowed(perk));
        Signals.bool(PlApp.APP, "cos_wear", app -> allowed("hat") && view().has("head_empty") && view().get("head_empty").getAsBoolean()
                && slots().stream().anyMatch(s -> s.index == selectedSlot && s.count > 0));
        Signals.bool(PlApp.APP, "cos_title_apply", app -> titles().stream().anyMatch(t -> t.key.equals(value("title", title)) && t.allowed));
        Signals.text(PlApp.APP, "cos_title_selected", PdaWidgets.TEXT_MAX, app -> "SELECTED: " + titles().stream().filter(t -> t.key.equals(value("title", title))).map(Title::label).findFirst().orElse("NONE"));
        Signals.items(PlApp.APP, "cos_titles", app -> titles(), TITLE);
        Signals.text(TITLE, "label", PdaWidgets.TEXT_MAX, t -> t.label + (t.allowed ? "" : " [rank/grant required]"));
        Signals.items(PlApp.APP, "cos_slots", app -> slots(), SLOT);
        Signals.text(SLOT, "label", PdaWidgets.TEXT_MAX, s -> (s.index + 1) + ":" + (s.count == 0 ? "-" : s.name));
        Signals.bool(SLOT, "filled", s -> s.count > 0);
    }

    private List<Title> titles() {
        var result = new ArrayList<Title>();
        result.add(new Title("", "NONE", "", true));
        var view = view();
        if (view.has("titles")) for (var item : view.getAsJsonArray("titles")) {
            var t = item.getAsJsonObject();
            result.add(new Title(PdaClientState.text(t, "key"), PdaClientState.text(t, "label"), PdaClientState.text(t, "formatted"), t.get("allowed").getAsBoolean()));
        }
        return result;
    }

    private List<Slot> slots() {
        var result = new ArrayList<Slot>();
        var view = view();
        if (view.has("slots")) for (var item : view.getAsJsonArray("slots")) {
            var slot = item.getAsJsonObject();
            result.add(new Slot((int) PdaClientState.number(slot, "slot"), PdaClientState.text(slot, "name"), (int) PdaClientState.number(slot, "count")));
        }
        return result;
    }

    private Component preview() {
        var view = view();
        String name = value("nick", nick);
        if (!CosmeticNames.valid(name, (int) PdaClientState.number(view, "nick_limit"), List.of(), List.of())) name = PdaClientState.text(view, "realname");
        String setting = tab == 1 ? CosmeticNames.gradient(from, to) : value("color", color);
        String selectedTitle = value("title", title);
        String formattedTitle = titles().stream().filter(t -> t.key.equals(selectedTitle)).map(Title::formatted).findFirst().orElse("");
        return Text.format(PdaClientState.text(view, "prefix"))
                .append(Text.format(Formatting.colorize(setting, name)))
                .append(Text.format(formattedTitle.isEmpty() ? "" : " " + formattedTitle))
                .append(Text.format(PdaClientState.text(view, "suffix")));
    }

    private WidgetDef previewWidget() {
        return binder -> new Widget() {
            public void measure(UiEval ev) { width = 308; height = 12; }
            public void draw(UiEval ev, float x, float y, float w, float h) {
                ev.canvas.text(preview().getVisualOrderText(), x, y, 0xffd2d6cc, false);
            }
        };
    }

    private WidgetDef button(float width, float height, String label, String enabled, UiAction action) {
        return new Button(action, enabled, PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 3, 2, width, height, Point.CENTER,
                new com.wf.wflib.ui.widget.Text(label, PdaShell.TEXT, false, "1", Float.toString(width - 8)));
    }

    private WidgetDef tabButton(String label, UiAction action, boolean selected) {
        return new Button(action, "true", selected ? PdaShell.PRESS : PdaShell.PANEL,
                PdaShell.HOVER, PdaShell.PRESS, selected ? PdaShell.ACCENT : PdaShell.EDGE,
                1, 0, 4, 2, 70, 20, Point.CENTER,
                new com.wf.wflib.ui.widget.Text(label, selected ? PdaShell.ACCENT : PdaShell.TEXT, false, "1", "62"));
    }

    public WidgetDef build() {
        var tabs = new ArrayList<WidgetDef>();
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            UiAction action = item -> tab = index;
            tabs.add(when("pda_pl.cos_tab == " + index, tabButton(TABS[i], action, true), tabButton(TABS[i], action, false)));
        }
        var swatches = new ArrayList<WidgetDef>();
        for (var hex : COLORS) swatches.add(new Button(item -> color = hex, "true", "#ff" + hex.substring(1), "#ff" + hex.substring(1), "#ff" + hex.substring(1), PdaShell.EDGE,
                1, 0, 0, 0, 20, 18, Point.CENTER, text("", PdaShell.TEXT)));
        var presets = new ArrayList<WidgetDef>();
        String[][] pairs = {{"SUNSET", "#ff5555", "#ffaa00"}, {"OCEAN", "#55ffff", "#5555ff"}, {"FOREST", "#00aa00", "#ffff55"}, {"ROYAL", "#aa00aa", "#ff55ff"}};
        for (var pair : pairs) presets.add(button(84, 20, pair[0], "true", item -> { from = pair[1]; to = pair[2]; }));
        WidgetDef titleRow = button(344, 20, "{pda_pl_cos_title.label}", "true", item -> title = ((Title) item).key);
        WidgetDef slot = new Button(item -> selectedSlot = ((Slot) item).index, "pda_pl_cos_slot.filled", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 1, 1, 38, 15, Point.CENTER, new com.wf.wflib.ui.widget.Text("{pda_pl_cos_slot.label}", PdaShell.TEXT, false, "0.75", "34"));
        return stack(
                at("8", "6", text("PREVIEW", PdaShell.DIM)), at("62", "6", clip(308, 12, previewWidget())),
                at("8", "24", row(4, tabs.toArray(WidgetDef[]::new))),
                at("8", "51", new com.wf.wflib.ui.widget.Text("{pda_pl.cos_lock}", PdaShell.DIM, false, "1", "366")),
                when("pda_pl.cos_tab == 0", stack(
                        at("8", "70", row(3, swatches.toArray(WidgetDef[]::new))),
                        at("8", "98", button(192, 22, "CUSTOM: {pda_pl.cos_color}", "true", item -> prompt("Name colour (#rrggbb or &a)", 8, value("color", color), input -> {
                            try { color = CosmeticNames.color(input); } catch (IllegalArgumentException e) { PdaClientState.result(id(), false, "Use #rrggbb or a colour code such as &a."); }
                        }))),
                        at("8", "136", button(100, 22, "APPLY COLOUR", "pda_pl.cos_namecolor_allowed", item -> send("color", value("color", color)))),
                        at("116", "136", button(100, 22, "RESET COLOUR", "true", item -> { color = null; send("color", "off"); })))),
                when("pda_pl.cos_tab == 1", stack(
                        at("8", "70", button(170, 22, "FROM: {pda_pl.cos_from}", "true", item -> prompt("Gradient start (#rrggbb)", 8, from, input -> {
                            try { from = CosmeticNames.color(input); } catch (IllegalArgumentException e) { PdaClientState.result(id(), false, "Use #rrggbb or a colour code such as &a."); }
                        }))),
                        at("186", "70", button(170, 22, "TO: {pda_pl.cos_to}", "true", item -> prompt("Gradient end (#rrggbb)", 8, to, input -> {
                            try { to = CosmeticNames.color(input); } catch (IllegalArgumentException e) { PdaClientState.result(id(), false, "Use #rrggbb or a colour code such as &a."); }
                        }))),
                        at("8", "102", row(4, presets.toArray(WidgetDef[]::new))),
                        at("8", "136", button(112, 22, "APPLY GRADIENT", "pda_pl.cos_namegradient_allowed", item -> send("gradient", from, to))),
                        at("128", "136", button(100, 22, "RESET COLOUR", "true", item -> { color = null; send("color", "off"); })))),
                when("pda_pl.cos_tab == 2", stack(
                        at("8", "70", button(264, 22, "{pda_pl.cos_nick}", "true", item -> prompt("Nickname (letters, numbers, underscore)",
                                (int) Math.max(1, Math.min(64, PdaClientState.number(view(), "nick_limit"))), value("nick", nick), input -> nick = input))),
                        at("8", "104", text("Letters, numbers and underscore only.", PdaShell.DIM)),
                        at("8", "136", button(100, 22, "APPLY NICK", "pda_pl.cos_nick_allowed", item -> send("nick", value("nick", nick)))),
                        at("116", "136", button(100, 22, "CLEAR NICK", "true", item -> { nick = null; send("nick", "off"); })))),
                when("pda_pl.cos_tab == 3", stack(
                        at("8", "68", list("pda_pl.cos_titles", 20, 2, "360", "88", PdaShell.EDGE, titleRow)),
                        at("8", "161", text("{pda_pl.cos_title_selected}", PdaShell.ACCENT)),
                        at("8", "174", button(100, 18, "APPLY TITLE", "pda_pl.cos_title_apply", item -> send("title", value("title", title).isEmpty() ? "off" : value("title", title)))))),
                when("pda_pl.cos_tab == 4", stack(
                        at("8", "68", button(242, 20, "{pda_pl.cos_price}", "pda_pl.cos_skull_allowed", item -> send("skull"))),
                        at("8", "94", new com.wf.wflib.ui.widget.Text("{pda_pl.cos_hat}", PdaShell.DIM, false, "1", "366")),
                        at("8", "108", grid("pda_pl.cos_slots", 9, 2, slot)),
                        at("8", "174", button(148, 16, "WEAR SELECTED AS HAT", "pda_pl.cos_wear", item -> PdaClientSetup.send(id(), "hat", List.of(), selectedSlot))),
                        at("164", "178", text("Helmet slot must be empty.", PdaShell.DIM)))));
    }
}
