package com.silentyeti.pluginless.pda;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiAction;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

import static com.silentyeti.pluginless.pda.PdaWidgets.at;
import static com.silentyeti.pluginless.pda.PdaWidgets.button;
import static com.silentyeti.pluginless.pda.PdaWidgets.card;
import static com.silentyeti.pluginless.pda.PdaWidgets.chip;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.repeat;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * Auction house: BROWSE and MINE share a list and a detail pane (buy, bid, cancel, watch); SELL picks an inventory
 * slot, a mode and a price. The price comes from the shared amount bar. The server keeps the category, sort, search
 * and page, so each snapshot is one page of listings.
 */
final class AuctionClientPage implements PdaClientPage {
    private static final AuctionClientPage INSTANCE = new AuctionClientPage();
    private static final Subject<AuctionClientPage> AUC = Subject.register("pda_pl_auc", AuctionClientPage.class, () -> INSTANCE);
    private static final Subject<Cat> CAT = Subject.register("pda_pl_auc_cat", Cat.class);
    private static final Subject<Row> ROW = Subject.register("pda_pl_auc_row", Row.class);
    private static final Subject<Slot> SLOT = Subject.register("pda_pl_auc_slot", Slot.class);
    private static final AmountBar BAR = new AmountBar("pda_pl_auc", AuctionClientPage::view, 10_000, 100, 1_000, 10_000, 100_000, 1_000_000);

    record Cat(String name, String label, boolean selected) {}
    record Row(long id, String label, boolean selected) {}
    record Slot(int index, String label, boolean selected) {}

    private static int tab; // 0 browse, 1 mine, 2 sell
    private static String category = "";
    private static String sort = "";
    private static String search = "";
    private static int pageNumber;
    private static long selected;
    private static int slot = -1;
    private static boolean auctionMode;
    private static long quoted = -1;
    private static boolean opened;
    private static int ticks;

    public String id() { return "auction"; }

    public String title() { return "AUCTION"; }

    private static JsonObject view() { return PdaClientState.view("auction"); }

    private static JsonArray array(String key) { return view().has(key) ? view().getAsJsonArray(key) : new JsonArray(); }

    private static JsonObject current() {
        for (var element : array(tab == 1 ? "mine" : "listings")) if (element.getAsJsonObject().get("id").getAsLong() == selected) return element.getAsJsonObject();
        return null;
    }

    private static String time(long seconds) {
        return seconds >= 3600 ? seconds / 3600 + "H" : seconds >= 60 ? seconds / 60 + "M" : seconds + "S";
    }

    private static String shorten(String name, int max) { return name.length() <= max ? name : name.substring(0, max - 1) + "."; }

    private static void query() {
        quoted = BAR.amount;
        PdaClientSetup.send("auction", "query", category, sort, search, Integer.toString(pageNumber), Long.toString(tab == 2 ? BAR.amount : 0));
    }

    private static long number(JsonObject o, String key) { return PdaClientState.number(o, key); }

    private static boolean flag(JsonObject o, String key) { return o.has(key) && o.get(key).getAsBoolean(); }

    public void signals() {
        BAR.signals(AUC);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            boolean open = PdaClientState.page().equals("auction");
            if (!open) { opened = false; return; }
            // On open the server may have forgotten the query (reconnect): resend it; while selling, keep the fee quote current.
            if (!opened) { opened = true; query(); return; }
            if (++ticks % 5 == 0 && tab == 2 && quoted != BAR.amount) query();
        });
        Signals.bool(AUC, "tab_browse", a -> tab == 0);
        Signals.bool(AUC, "tab_mine", a -> tab == 1);
        Signals.bool(AUC, "tab_sell", a -> tab == 2);
        Signals.bool(AUC, "tab_list", a -> tab != 2);
        Signals.text(AUC, "mine_label", 12, a -> "MINE " + array("mine").size());
        Signals.text(AUC, "boxed", 16, a -> "COLLECT " + number(view(), "boxed"));
        Signals.bool(AUC, "has_boxed", a -> number(view(), "boxed") > 0);
        Signals.items(AUC, "cats", a -> cats(), CAT);
        Signals.items(AUC, "rows", a -> rows(), ROW);
        Signals.bool(AUC, "no_rows", a -> rows().isEmpty());
        Signals.items(AUC, "slots", a -> slots(), SLOT);
        for (var option : new String[][]{{"sort_new", ""}, {"sort_price", "price"}, {"sort_expensive", "expensive"}, {"sort_oldest", "oldest"}}) {
            Signals.bool(AUC, option[0], a -> sort.equals(option[1]));
        }
        Signals.text(AUC, "search", 24, a -> search.isEmpty() ? "SEARCH" : search.toUpperCase());
        Signals.text(AUC, "pager", 16, a -> "PAGE " + (number(view().has("query") ? view().getAsJsonObject("query") : new JsonObject(), "page") + 1)
                + "/" + number(view().has("query") ? view().getAsJsonObject("query") : new JsonObject(), "pages"));
        Signals.bool(AUC, "has_sel", a -> current() != null);
        Signals.text(AUC, "sel_name", 28, a -> current() == null ? "" : shorten(PdaClientState.text(current(), "name"), 28));
        Signals.text(AUC, "sel_who", 32, a -> current() == null ? "" : number(current(), "count") + "X  BY " + shorten(PdaClientState.text(current(), "seller").toUpperCase(), 12));
        Signals.text(AUC, "sel_price", 32, a -> current() == null ? "" : (flag(current(), "auction") ? (flag(current(), "bids") ? "BID " : "FROM ") : "PRICE ")
                + PdaClientState.money(view(), number(current(), "price")));
        Signals.text(AUC, "sel_next", 32, a -> current() == null || !flag(current(), "auction") ? ""
                : (flag(current(), "leading") ? "YOU LEAD" : "MIN " + PdaClientState.money(view(), number(current(), "minBid"))));
        Signals.text(AUC, "sel_time", 16, a -> current() == null ? "" : "ENDS " + time(number(current(), "seconds")));
        Signals.bool(AUC, "can_buy", a -> current() != null && !flag(current(), "mine") && !flag(current(), "auction"));
        Signals.bool(AUC, "can_bid", a -> current() != null && !flag(current(), "mine") && flag(current(), "auction") && BAR.amount >= number(current(), "minBid"));
        Signals.bool(AUC, "can_cancel", a -> current() != null && flag(current(), "mine"));
        Signals.bool(AUC, "can_watch", a -> current() != null);
        Signals.bool(AUC, "mode_fixed", a -> !auctionMode);
        Signals.bool(AUC, "mode_auction", a -> auctionMode);
        Signals.bool(AUC, "can_list", a -> slot >= 0 && BAR.amount >= number(view(), "min") && BAR.amount <= number(view(), "max"));
        Signals.text(AUC, "fee", 40, a -> BAR.amount <= 0 ? "" : "FEE " + PdaClientState.money(view(), number(view(), "fee")) + "  TAX " + (view().has("tax") ? view().get("tax").getAsDouble() : 0) + "%");
        Signals.text(AUC, "limits", 40, a -> "MIN " + PdaClientState.money(view(), number(view(), "min")) + "  SLOTS " + number(view(), "slots") + "  " + number(view(), "hours") + "H");
        Signals.text(CAT, "label", 10, Cat::label);
        Signals.bool(CAT, "selected", Cat::selected);
        Signals.text(ROW, "label", 56, Row::label);
        Signals.bool(ROW, "selected", Row::selected);
        Signals.text(SLOT, "label", 40, Slot::label);
        Signals.bool(SLOT, "selected", Slot::selected);
    }

    private static List<Cat> cats() {
        var result = new ArrayList<Cat>();
        result.add(new Cat("", "ALL", category.isEmpty()));
        for (var name : array("categories")) {
            var value = name.getAsString();
            result.add(new Cat(value, shorten(value.toUpperCase(), 8), value.equals(category)));
        }
        return result;
    }

    private static List<Row> rows() {
        var result = new ArrayList<Row>();
        for (var element : array(tab == 1 ? "mine" : "listings")) {
            var row = element.getAsJsonObject();
            long id = number(row, "id");
            result.add(new Row(id, shorten(PdaClientState.text(row, "name"), 20) + " X" + number(row, "count") + "  "
                    + PdaClientState.money(view(), number(row, "price")) + (flag(row, "auction") ? " BID" : "") + "  " + time(number(row, "seconds")), id == selected));
        }
        return result;
    }

    private static List<Slot> slots() {
        var result = new ArrayList<Slot>();
        var player = Minecraft.getInstance().player;
        if (player == null) return result;
        for (int i = 0; i < 36; i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) result.add(new Slot(i, shorten(stack.getHoverName().getString(), 24) + " X" + stack.getCount(), i == slot));
        }
        return result;
    }

    public WidgetDef build() {
        UiAction pickCategory = item -> { category = ((Cat) item).name(); pageNumber = 0; selected = 0; query(); };
        UiAction pickRow = item -> {
            selected = ((Row) item).id();
            var chosen = current();
            if (chosen != null && flag(chosen, "auction")) BAR.amount = number(chosen, "minBid");
        };
        UiAction pickSlot = item -> slot = ((Slot) item).index();
        var tree = new ArrayList<WidgetDef>();
        tree.add(at(8, 4, chip("BROWSE", "pda_pl_auc.tab_browse", () -> { tab = 0; selected = 0; query(); })));
        tree.add(at(52, 4, chip("{pda_pl_auc.mine_label}", "pda_pl_auc.tab_mine", () -> { tab = 1; selected = 0; query(); })));
        tree.add(at(96, 4, chip("SELL", "pda_pl_auc.tab_sell", () -> { tab = 2; query(); })));
        tree.add(at(300, 4, button("{pda_pl_auc.boxed}", "pda_pl_auc.has_boxed", () -> PdaClientSetup.send("auction", "collect"))));

        // BROWSE / MINE: filters, list, pager, detail
        tree.add(when("pda_pl_auc.tab_list", stack(
                when("pda_pl_auc.tab_browse", stack(
                        at(8, 20, repeat("pda_pl_auc.cats", false, 2, PdaWidgets.row("{pda_pl_auc_cat.label}", 40, "pda_pl_auc_cat.selected", pickCategory))),
                        at(8, 33, chip("NEW", "pda_pl_auc.sort_new", () -> { sort = ""; query(); })),
                        at(34, 33, chip("PRICE+", "pda_pl_auc.sort_price", () -> { sort = "price"; query(); })),
                        at(70, 33, chip("PRICE-", "pda_pl_auc.sort_expensive", () -> { sort = "expensive"; query(); })),
                        at(106, 33, chip("OLD", "pda_pl_auc.sort_oldest", () -> { sort = "oldest"; query(); })),
                        at(134, 33, button("{pda_pl_auc.search}", () -> PdaWidgets.prompt("Search", search, typed -> { search = typed.trim(); pageNumber = 0; query(); }))))),
                at(8, 49, list("pda_pl_auc.rows", 11, 1, "226", "92", PdaShell.EDGE,
                        PdaWidgets.row("{pda_pl_auc_row.label}", 214, "pda_pl_auc_row.selected", pickRow))),
                when("pda_pl_auc.no_rows", at(12, 54, text("NOTHING LISTED", PdaShell.DIM))),
                when("pda_pl_auc.tab_browse", stack(
                        at(8, 144, button("<", () -> { if (pageNumber > 0) { pageNumber--; query(); } })),
                        at(26, 147, text("{pda_pl_auc.pager}", PdaShell.DIM)),
                        at(80, 144, button(">", () -> { pageNumber++; query(); })))),
                card(242, 20, 134, 108, "DETAIL"),
                when("pda_pl_auc.has_sel", stack(
                        at(247, 32, text("{pda_pl_auc.sel_name}", PdaShell.ACCENT)),
                        at(247, 44, text("{pda_pl_auc.sel_who}", PdaShell.TEXT)),
                        at(247, 56, text("{pda_pl_auc.sel_price}", PdaShell.TEXT)),
                        at(247, 66, text("{pda_pl_auc.sel_next}", PdaShell.DIM)),
                        at(247, 76, text("{pda_pl_auc.sel_time}", PdaShell.DIM)),
                        at(247, 92, button("BUY", "pda_pl_auc.can_buy", () -> PdaClientSetup.send("auction", "buy", Long.toString(selected)))),
                        at(272, 92, button("BID", "pda_pl_auc.can_bid", () -> PdaClientSetup.send("auction", "bid", Long.toString(selected), Long.toString(BAR.amount)))),
                        at(297, 92, button("CANCEL", "pda_pl_auc.can_cancel", () -> PdaClientSetup.send("auction", "cancel", Long.toString(selected)))),
                        at(247, 108, button("WATCH", "pda_pl_auc.can_watch", () -> {
                            var chosen = current();
                            if (chosen != null) PdaClientSetup.send("auction", "watch", PdaClientState.text(chosen, "item"));
                        })),
                        at(284, 108, button("UNWATCH", "pda_pl_auc.can_watch", () -> {
                            var chosen = current();
                            if (chosen != null) PdaClientSetup.send("auction", "unwatch", PdaClientState.text(chosen, "item"));
                        })))),
                when("pda_pl_auc.has_sel", stack(), at(247, 40, text("PICK A LISTING", PdaShell.DIM))))));

        // SELL: inventory, mode, fee
        tree.add(when("pda_pl_auc.tab_sell", stack(
                card(8, 20, 226, 120, "PICK AN ITEM FROM YOUR INVENTORY"),
                at(13, 34, list("pda_pl_auc.slots", 11, 1, "214", "100", PdaShell.EDGE,
                        PdaWidgets.row("{pda_pl_auc_slot.label}", 202, "pda_pl_auc_slot.selected", pickSlot))),
                card(242, 20, 134, 120, "LISTING"),
                at(247, 34, chip("FIXED PRICE", "pda_pl_auc.mode_fixed", () -> auctionMode = false)),
                at(247, 50, chip("BIDDING", "pda_pl_auc.mode_auction", () -> auctionMode = true)),
                at(247, 70, text("{pda_pl_auc.fee}", PdaShell.TEXT)),
                at(247, 82, text("{pda_pl_auc.limits}", PdaShell.DIM)),
                at(247, 104, button("LIST IT", "pda_pl_auc.can_list", () -> {
                    if (slot >= 0) PdaClientSetup.send("auction", auctionMode ? "auction" : "sell", java.util.List.of(Long.toString(BAR.amount)), slot);
                })))));

        tree.addAll(BAR.widgets(8, 160, new AmountBar.Shortcut("MIN", 22, () -> {
            var chosen = current();
            return tab == 2 ? number(view(), "min") : chosen == null ? 0 : flag(chosen, "auction") ? number(chosen, "minBid") : number(chosen, "price");
        })));
        return stack(tree.toArray(WidgetDef[]::new));
    }
}
