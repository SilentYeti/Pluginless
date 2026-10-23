package com.silentyeti.pluginless.pda;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiAction;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;

import java.util.ArrayList;
import java.util.List;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.button;
import static com.wf.wflib.ui.Ui.repeat;
import static com.wf.wflib.ui.Ui.sized;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/** Ranks: where you stand on the track, then perks, kits (claimable) and playtime rewards in three tabs. */
final class RanksClientPage implements PdaClientPage {
    record Line(String id, String detail, String state, boolean ready) {}

    private static final Subject<Line> PERK = Subject.register("pda_pl_rk_perk", Line.class);
    private static final Subject<Line> KIT = Subject.register("pda_pl_rk_kit", Line.class);
    private static final Subject<Line> REWARD = Subject.register("pda_pl_rk_reward", Line.class);
    private static final String[] TABS = {"PERKS", "KITS", "REWARDS"};

    private static volatile int tab;

    public String id() { return "ranks"; }

    public String title() { return "RANKS"; }

    private static JsonObject view() { return PdaClientState.view("ranks"); }

    public void signals() {
        Signals.num(PlApp.APP, "rk_tab", app -> tab);
        Signals.text(PlApp.APP, "rk_head", 56, app -> {
            var view = view();
            long seconds = PdaClientState.number(view, "seconds");
            return ("RANK " + PdaClientState.text(view, "rank") + "   PLAYTIME " + hours(seconds)).toUpperCase();
        });
        Signals.text(PlApp.APP, "rk_track", 72, app -> track());
        Signals.text(PlApp.APP, "rk_next", 56, app -> next());
        Signals.bool(PlApp.APP, "rk_no_perks", app -> lines("perks").isEmpty());
        Signals.bool(PlApp.APP, "rk_no_kits", app -> lines("kits").isEmpty());
        Signals.bool(PlApp.APP, "rk_no_rewards", app -> lines("rewards").isEmpty());
        Signals.items(PlApp.APP, "rk_perks", app -> lines("perks"), PERK);
        Signals.items(PlApp.APP, "rk_kits", app -> lines("kits"), KIT);
        Signals.items(PlApp.APP, "rk_rewards", app -> lines("rewards"), REWARD);
        for (var subject : List.of(PERK, KIT, REWARD)) {
            Signals.text(subject, "id", 24, Line::id);
            Signals.text(subject, "detail", 40, Line::detail);
            Signals.text(subject, "state", 14, Line::state);
            Signals.bool(subject, "ready", Line::ready);
        }
    }

    private static String hours(long seconds) {
        return seconds / 3600 + "H " + (seconds / 60) % 60 + "M";
    }

    private static String span(long seconds) {
        return seconds >= 3600 ? hours(seconds) : seconds >= 60 ? seconds / 60 + "M " + seconds % 60 + "S" : seconds + "S";
    }

    private static String track() {
        var view = view();
        if (!view.has("track") || !view.get("track").isJsonObject()) return "NOT ON A PROMOTION TRACK";
        var track = view.getAsJsonObject("track");
        var ranks = track.getAsJsonArray("ranks");
        int index = (int) PdaClientState.number(track, "index");
        var path = new StringBuilder();
        for (int i = 0; i < ranks.size(); i++) {
            if (i > 0) path.append(" > ");
            path.append(i == index ? "[" + ranks.get(i).getAsString() + "]" : ranks.get(i).getAsString());
        }
        return path.toString().toUpperCase();
    }

    private static String next() {
        var view = view();
        if (!view.has("track") || !view.get("track").isJsonObject()) return "";
        var track = view.getAsJsonObject("track");
        if (!track.has("next")) return "TOP OF THE TRACK";
        String next = PdaClientState.text(track, "next").toUpperCase();
        long needed = PdaClientState.number(track, "nextSeconds");
        long have = PdaClientState.number(view, "seconds");
        if (!track.has("nextSeconds")) return "NEXT: " + next;
        return have >= needed ? "NEXT: " + next + " ANY MOMENT" : "NEXT: " + next + " IN " + span(needed - have);
    }

    private static List<Line> lines(String key) {
        var view = view();
        var result = new ArrayList<Line>();
        if (!view.has(key) || !view.get(key).isJsonArray()) return result;
        JsonArray array = view.getAsJsonArray(key);
        for (var element : array) {
            var row = element.getAsJsonObject();
            switch (key) {
                case "perks" -> result.add(new Line(PdaClientState.text(row, "name").toUpperCase(), PdaClientState.text(row, "value").toUpperCase(), "", false));
                case "kits" -> {
                    long left = PdaClientState.number(row, "left");
                    result.add(new Line(PdaClientState.text(row, "id").toUpperCase(), reward(view, row), left == 0 ? "READY" : span(left), left == 0));
                }
                default -> {
                    long left = PdaClientState.number(row, "left");
                    result.add(new Line(PdaClientState.text(row, "id").toUpperCase(), reward(view, row),
                            left < 0 ? "DONE" : left == 0 ? "DUE" : span(left), false));
                }
            }
        }
        return result;
    }

    private static String reward(JsonObject view, JsonObject row) {
        long money = PdaClientState.number(row, "money");
        String items = PdaClientState.text(row, "items");
        String text = money > 0 ? PdaClientState.money(view, money) : "";
        if (!items.isEmpty()) text = text.isEmpty() ? items : text + " + " + items;
        return text.length() > 40 ? text.substring(0, 39) + "." : text;
    }

    private static WidgetDef tabButton(int index) {
        UiAction pick = item -> tab = index;
        return at(Integer.toString(8 + index * 64), "40", button(PdaShell.PANEL, PdaShell.HOVER, 4, pick,
                when("pda_pl.rk_tab == " + index, text(TABS[index], PdaShell.ACCENT), text(TABS[index], PdaShell.TEXT))));
    }

    public WidgetDef build() {
        UiAction claim = item -> PdaClientSetup.send("ranks", "kit", ((Line) item).id().toLowerCase());
        WidgetDef claimButton = new Button(claim, "pda_pl_rk_kit.ready", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 0, 0, 48, 14, Point.CENTER, text("CLAIM", PdaShell.TEXT));
        return stack(
                at("8", "4", text("{pda_pl.rk_head}", PdaShell.ACCENT)),
                at("8", "16", text("{pda_pl.rk_track}", PdaShell.TEXT)),
                at("8", "28", text("{pda_pl.rk_next}", PdaShell.DIM)),
                tabButton(0), tabButton(1), tabButton(2),
                when("pda_pl.rk_tab == 0", at("8", "62", stack(
                        when("pda_pl.rk_no_perks", text("NO PERKS", PdaShell.DIM)),
                        repeat("pda_pl.rk_perks", true, 3, sized(368, 12, stack(
                                at("0", "0", text("{pda_pl_rk_perk.id}", PdaShell.TEXT)),
                                at("220", "0", text("{pda_pl_rk_perk.detail}", PdaShell.ACCENT)))))))),
                when("pda_pl.rk_tab == 1", at("8", "62", stack(
                        when("pda_pl.rk_no_kits", text("NO KITS FOR YOUR RANK", PdaShell.DIM)),
                        repeat("pda_pl.rk_kits", true, 3, sized(368, 16, stack(
                                at("0", "2", text("{pda_pl_rk_kit.id}", PdaShell.TEXT)),
                                at("84", "2", text("{pda_pl_rk_kit.detail}", PdaShell.DIM)),
                                at("262", "2", text("{pda_pl_rk_kit.state}", PdaShell.ACCENT)),
                                at("320", "0", claimButton))))))),
                when("pda_pl.rk_tab == 2", at("8", "62", stack(
                        when("pda_pl.rk_no_rewards", text("NO PLAYTIME REWARDS", PdaShell.DIM)),
                        repeat("pda_pl.rk_rewards", true, 3, sized(368, 12, stack(
                                at("0", "0", text("{pda_pl_rk_reward.id}", PdaShell.TEXT)),
                                at("84", "0", text("{pda_pl_rk_reward.detail}", PdaShell.DIM)),
                                at("300", "0", text("{pda_pl_rk_reward.state}", PdaShell.ACCENT)))))))));
    }
}
