package com.silentyeti.pluginless.pda;

import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.pda.client.TextPromptScreen;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.button;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.row;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/** Homes, warps, players to teleport to, and the other travel commands. Players only: no warp or spawn editing here. */
final class QolClientPage implements PdaClientPage {
    private static final String[] TABS = {"HOMES", "WARPS", "PLAYERS", "MORE"};

    /** One list row: a name, a detail line and up to two verbs sent with the key as the argument. */
    record Entry(String name, String detail, String key, String verb, String label, String second, String secondLabel) {}

    private static final Subject<Entry> ENTRY = Subject.register("pda_pl_qol", Entry.class);
    private static volatile int tab;

    public String id() { return "qol"; }

    public String title() { return "HOME & WARPS"; }

    public void signals() {
        Signals.num(PlApp.APP, "qol_tab", app -> tab);
        Signals.items(PlApp.APP, "qol_rows", app -> rows(), ENTRY);
        Signals.text(ENTRY, "name", 24, Entry::name);
        Signals.text(ENTRY, "detail", 28, Entry::detail);
        Signals.text(ENTRY, "label", 8, Entry::label);
        Signals.text(ENTRY, "label2", 8, Entry::secondLabel);
        Signals.bool(ENTRY, "has2", entry -> !entry.second().isEmpty());
        Signals.text(PlApp.APP, "qol_homes", 24, app -> {
            var view = PdaClientState.view("qol");
            return "HOMES " + (view.has("homes") ? view.getAsJsonArray("homes").size() : 0) + "/" + PdaClientState.number(view, "homeLimit");
        });
        Signals.bool(PlApp.APP, "qol_request", app -> PdaClientState.view("qol").has("request"));
        Signals.text(PlApp.APP, "qol_request_text", 48, app -> {
            var view = PdaClientState.view("qol");
            if (!view.has("request")) return "";
            JsonObject request = view.getAsJsonObject("request");
            return PdaClientState.text(request, "from") + (request.get("here").getAsBoolean() ? " wants you to come to them" : " wants to come to you");
        });
    }

    private static List<Entry> rows() {
        var view = PdaClientState.view("qol");
        var result = new ArrayList<Entry>();
        switch (tab) {
            case 0 -> {
                if (view.has("homes")) for (var element : view.getAsJsonArray("homes")) {
                    var home = element.getAsJsonObject();
                    var name = PdaClientState.text(home, "name");
                    result.add(new Entry(name, PdaClientState.text(home, "where"), name, "home", "GO", "delhome", "DELETE"));
                }
            }
            case 1 -> {
                if (view.has("warps")) for (var element : view.getAsJsonArray("warps"))
                    result.add(new Entry(element.getAsString(), "", element.getAsString(), "warp", "GO", "", ""));
            }
            case 2 -> {
                if (view.has("players")) for (var element : view.getAsJsonArray("players"))
                    result.add(new Entry(element.getAsString(), "", element.getAsString(), "tpa", "TO THEM", "tpahere", "TO ME"));
            }
            default -> { }
        }
        return result;
    }

    private static WidgetDef tabButton(int index) {
        return when("pda_pl.qol_tab == " + index,
                button(PdaShell.HOVER, PdaShell.HOVER, 4, item -> {}, text(TABS[index], PdaShell.ACCENT)),
                button(PdaShell.PANEL, PdaShell.HOVER, 4, item -> tab = index, text(TABS[index], PdaShell.TEXT)));
    }

    private static WidgetDef action(String label, String verb) {
        return button(PdaShell.PANEL, PdaShell.HOVER, 4, item -> PdaClientSetup.send("qol", verb), text(label, PdaShell.TEXT));
    }

    public WidgetDef build() {
        var entryRow = stack(
                at("0", "3", text("{pda_pl_qol.name}", PdaShell.TEXT)),
                at("100", "3", text("{pda_pl_qol.detail}", PdaShell.DIM)),
                at("250", "0", button(PdaShell.PANEL, PdaShell.HOVER, 4, item -> {
                    var entry = (Entry) item;
                    PdaClientSetup.send("qol", entry.verb(), entry.key());
                }, text("{pda_pl_qol.label}", PdaShell.TEXT))),
                when("pda_pl_qol.has2", at("306", "0", button(PdaShell.PANEL, PdaShell.HOVER, 4, item -> {
                    var entry = (Entry) item;
                    PdaClientSetup.send("qol", entry.second(), entry.key());
                }, text("{pda_pl_qol.label2}", PdaShell.ALERT)))));
        return stack(
                at("8", "4", row(4, tabButton(0), tabButton(1), tabButton(2), tabButton(3))),
                when("pda_pl.qol_request", stack(
                        at("8", "26", text("{pda_pl.qol_request_text}", PdaShell.ACCENT)),
                        at("250", "22", action("ACCEPT", "tpaccept")),
                        at("306", "22", action("DENY", "tpdeny")))),
                when("pda_pl.qol_tab < 3", stack(
                        at("8", "40", list("pda_pl.qol_rows", 16, 2, "368", "122", PdaShell.EDGE, entryRow)),
                        when("pda_pl.qol_tab == 0", stack(
                                at("8", "166", text("{pda_pl.qol_homes}", PdaShell.DIM)),
                                at("250", "162", button(PdaShell.PANEL, PdaShell.HOVER, 4,
                                        item -> TextPromptScreen.open(Component.literal("Name this home here"), 24, "",
                                                name -> PdaClientSetup.send("qol", "sethome", name)),
                                        text("SET HOME HERE", PdaShell.ACCENT)))))),
                        stack(
                                at("8", "44", text("GO BACK TO WHERE YOU WERE BEFORE YOUR LAST TELEPORT OR DEATH", PdaShell.DIM)),
                                at("8", "58", action("BACK", "back")),
                                at("8", "86", action("SPAWN", "spawn")),
                                at("8", "114", action("RANDOM TELEPORT", "rtp")))));
    }
}
