package com.silentyeti.pluginless.pda;

import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiAction;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.column;
import static com.wf.wflib.ui.Ui.grid;
import static com.wf.wflib.ui.Ui.repeat;
import static com.wf.wflib.ui.Ui.row;
import static com.wf.wflib.ui.Ui.stack;
import static com.silentyeti.pluginless.pda.PdaWidgets.fit;
import static com.silentyeti.pluginless.pda.PdaWidgets.wrap;
import static com.silentyeti.pluginless.pda.PdaWidgets.text;
import static com.wf.wflib.ui.Ui.when;

/** Home: one tile per page with a live line from the server, then the latest notices. History and badge only, no toasts. */
final class HomeClientPage implements PdaClientPage {
    /** Page ids in tile order with their labels; a tile is live once its page is registered on the client. */
    private static final String[][] TILES = {
            {"bank", "BANK"}, {"auction", "AUCTION"}, {"market", "MARKET"}, {"stocks", "STOCKS"},
            {"companies", "COMPANIES"}, {"ranks", "RANKS"}, {"qol", "HOME & WARPS"}, {"cosmetics", "COSMETICS"}};
    private static final float TILE_W = 86;
    private static final float TILE_H = 40;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    record Tile(String page, String name, String line, boolean ready) {}
    record Note(String clock, String text, boolean unread) {}

    private static final Subject<Tile> TILE = Subject.register("pda_pl_tile", Tile.class);
    private static final Subject<Note> NOTE = Subject.register("pda_pl_note", Note.class);

    public String id() { return "home"; }

    public String title() { return "HOME"; }

    public void signals() {
        Signals.items(PlApp.APP, "tiles", app -> tiles(), TILE);
        Signals.items(PlApp.APP, "notes", app -> notes(), NOTE);
        Signals.bool(PlApp.APP, "has_notes", app -> !notes().isEmpty());
        Signals.bool(PlApp.APP, "has_unread", app -> PdaClientState.number(PdaClientState.view("home"), "unread") > 0);
        Signals.text(TILE, "name", PdaWidgets.TEXT_MAX, Tile::name);
        Signals.text(TILE, "line", PdaWidgets.TEXT_MAX, tile -> tile.ready() ? tile.line() : "SOON");
        Signals.bool(TILE, "ready", Tile::ready);
        Signals.text(NOTE, "clock", PdaWidgets.TEXT_MAX, Note::clock);
        Signals.text(NOTE, "text", PdaWidgets.TEXT_MAX, Note::text);
        Signals.bool(NOTE, "unread", Note::unread);
        Signals.text(PlApp.APP, "rank", PdaWidgets.TEXT_MAX, app -> PdaClientState.text(PdaClientState.view("home"), "rank").toUpperCase());
        Signals.text(PlApp.APP, "debt", PdaWidgets.TEXT_MAX, app -> {
            var view = PdaClientState.view("home");
            long debt = PdaClientState.number(view, "debt");
            return debt > 0 ? "DEBT " + PdaClientState.money(view, debt) : "";
        });
    }

    private static List<Tile> tiles() {
        var home = PdaClientState.view("home");
        JsonObject lines = home.has("tiles") && home.get("tiles").isJsonObject() ? home.getAsJsonObject("tiles") : new JsonObject();
        var open = new ArrayList<String>();
        for (var page : PdaClientSetup.pages()) open.add(page.id());
        var result = new ArrayList<Tile>();
        for (var tile : TILES) result.add(new Tile(tile[0], tile[1], PdaClientState.text(lines, tile[0]), open.contains(tile[0])));
        return result;
    }

    private static List<Note> notes() {
        var home = PdaClientState.view("home");
        var result = new ArrayList<Note>();
        if (!home.has("notices") || !home.get("notices").isJsonArray()) return result;
        for (var element : home.getAsJsonArray("notices")) {
            var notice = element.getAsJsonObject();
            result.add(new Note(CLOCK.format(Instant.ofEpochMilli(PdaClientState.number(notice, "time"))),
                    PdaClientState.text(notice, "text"), notice.has("unread") && notice.get("unread").getAsBoolean()));
        }
        return result;
    }

    public WidgetDef build() {
        UiAction go = item -> PdaClientSetup.open(((Tile) item).page());
        WidgetDef tile = new Button(go, "pda_pl_tile.ready", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 5, 4, TILE_W, TILE_H, Point.TOP_LEFT,
                column(3, fit("{pda_pl_tile.name}", PdaShell.TEXT, 76), fit("{pda_pl_tile.line}", PdaShell.DIM, 76)));
        UiAction read = item -> PdaClientSetup.send("home", "read");
        return stack(
                at("8", "6", text("{pda_pl.rank}", PdaShell.ACCENT)),
                at("120", "6", text("{pda_pl.debt}", PdaShell.ALERT)),
                at("8", "18", grid("pda_pl.tiles", 4, 8, tile)),
                at("8", "112", text("RECENT", PdaShell.DIM)),
                when("pda_pl.has_unread", at("330", "108", new Button(read, "true", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS,
                        PdaShell.EDGE, 1, 0, 4, 2, 0, 0, Point.TOP_LEFT, text("MARK READ", PdaShell.TEXT)))),
                when("pda_pl.has_notes",
                        at("8", "124", repeat("pda_pl.notes", true, 3, row(6,
                                text("{pda_pl_note.clock}", PdaShell.DIM),
                                when("pda_pl_note.unread", text("{pda_pl_note.text}", PdaShell.ACCENT),
                                        text("{pda_pl_note.text}", PdaShell.TEXT))))),
                        at("8", "124", text("NOTHING NEW", PdaShell.DIM))));
    }
}
