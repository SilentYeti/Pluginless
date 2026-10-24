package com.silentyeti.pluginless.pda;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiAction;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

import static com.silentyeti.pluginless.pda.PdaWidgets.at;
import static com.silentyeti.pluginless.pda.PdaWidgets.button;
import static com.silentyeti.pluginless.pda.PdaWidgets.card;
import static com.silentyeti.pluginless.pda.PdaWidgets.chip;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.row;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * Item market: a list of items (or your orders), a price chart and the best waiting orders for the chosen item, and
 * the four ways to trade it: instantly against waiting orders, or by leaving an order at your price.
 */
final class MarketClientPage implements PdaClientPage {
    private static final MarketClientPage INSTANCE = new MarketClientPage();
    private static final Subject<MarketClientPage> MKT = Subject.register("pda_pl_mkt", MarketClientPage.class, () -> INSTANCE);
    private static final Subject<Item> ITEM = Subject.register("pda_pl_mkt_item", Item.class);
    private static final Subject<Order> ORDER = Subject.register("pda_pl_mkt_order", Order.class);
    private static final Subject<Level> LEVEL = Subject.register("pda_pl_mkt_level", Level.class);
    private static final AmountBar BAR = new AmountBar("pda_pl_mkt", MarketClientPage::view, 100, 1, 10, 100, 1_000);
    private static final long[] QUANTITIES = {1, 8, 64, 1_000};

    record Item(String id, String label, boolean selected) {}
    record Order(long id, String label, boolean selected) {}
    record Level(String label) {}

    private static boolean ordersTab;
    private static long quantity = 1;
    private static long selectedOrder;
    private static String queried = "";
    private static boolean opened;

    public String id() { return "market"; }

    public String title() { return "MARKET"; }

    private static JsonObject view() { return PdaClientState.view("market"); }

    private static JsonArray array(String key) { return view().has(key) ? view().getAsJsonArray(key) : new JsonArray(); }

    private static long number(String key) { return PdaClientState.number(view(), key); }

    private static String asset() { return PdaClientState.text(view(), "asset"); }

    private static String name(String id, int max) {
        var plain = id.substring(id.indexOf(':') + 1).replace('_', ' ').replace('/', ' ').toUpperCase();
        return plain.length() <= max ? plain : plain.substring(0, max - 1) + ".";
    }

    private static String money(long cents) { return PdaClientState.money(view(), cents); }

    private static void query(String chosen) {
        queried = chosen + "/" + quantity;
        PdaClientSetup.send("market", "query", chosen, Long.toString(quantity));
    }

    private static void trade(String verb, boolean withPrice) {
        if (asset().isEmpty()) return;
        if (withPrice) PdaClientSetup.send("market", verb, asset(), Long.toString(quantity), Long.toString(BAR.amount));
        else PdaClientSetup.send("market", verb, asset(), Long.toString(quantity));
    }

    public void signals() {
        BAR.signals(MKT);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            boolean open = PdaClientState.page().equals("market");
            if (!open) { opened = false; return; }
            if (!opened) { opened = true; query(asset()); }
        });
        Signals.bool(MKT, "tab_items", m -> !ordersTab);
        Signals.bool(MKT, "tab_orders", m -> ordersTab);
        Signals.text(MKT, "orders_label", 12, m -> "ORDERS " + array("orders").size());
        Signals.items(MKT, "items", m -> items(), ITEM);
        Signals.items(MKT, "orders", m -> orders(), ORDER);
        Signals.items(MKT, "asks", m -> levels("asks"), LEVEL);
        Signals.items(MKT, "bids", m -> levels("bids"), LEVEL);
        Signals.text(MKT, "title", 28, m -> asset().isEmpty() ? "NO ITEMS" : name(asset(), 28));
        Signals.text(MKT, "last", 24, m -> {
            var chart = array("chart");
            return chart.isEmpty() ? "" : "LAST " + money(chart.get(chart.size() - 1).getAsLong());
        });
        Signals.text(MKT, "qty", 12, m -> "X" + quantity);
        for (int i = 0; i < QUANTITIES.length; i++) {
            long value = QUANTITIES[i];
            Signals.bool(MKT, "q" + i, m -> quantity == value);
        }
        Signals.text(MKT, "held", 24, m -> "YOU HOLD " + number("held") + "  BOXED " + number("goods"));
        Signals.text(MKT, "quote", 60, m -> {
            long buy = number("quoteBuy");
            long sell = number("quoteSell");
            return "NOW: BUY " + (buy > 0 ? "UP TO " + money(buy * quantity) : "NOT ENOUGH") + "  SELL " + (sell > 0 ? "~" + money(sell * quantity) : "NO BUYERS");
        });
        Signals.text(MKT, "fee", 24, m -> "FEE " + (view().has("fee") ? view().get("fee").getAsDouble() : 0) + "%");
        Signals.bool(MKT, "can_ibuy", m -> number("quoteBuy") > 0);
        Signals.bool(MKT, "can_isell", m -> number("quoteSell") > 0 && number("held") >= quantity);
        Signals.bool(MKT, "can_buy", m -> BAR.amount > 0 && !asset().isEmpty());
        Signals.bool(MKT, "can_sell", m -> BAR.amount > 0 && number("held") >= quantity);
        Signals.bool(MKT, "can_collect", m -> number("goods") > 0);
        Signals.bool(MKT, "can_cancel", m -> selectedOrder > 0);
        Signals.text(ITEM, "label", 40, Item::label);
        Signals.bool(ITEM, "selected", Item::selected);
        Signals.text(ORDER, "label", 56, Order::label);
        Signals.bool(ORDER, "selected", Order::selected);
        Signals.text(LEVEL, "label", 24, Level::label);
    }

    private static List<Item> items() {
        var result = new ArrayList<Item>();
        for (var element : array("items")) {
            var row = element.getAsJsonObject();
            var id = PdaClientState.text(row, "id");
            long last = PdaClientState.number(row, "last");
            result.add(new Item(id, name(id, 14) + (last > 0 ? "  " + money(last) : ""), id.equals(asset())));
        }
        return result;
    }

    private static List<Order> orders() {
        var result = new ArrayList<Order>();
        for (var element : array("orders")) {
            var row = element.getAsJsonObject();
            long id = PdaClientState.number(row, "id");
            result.add(new Order(id, (row.get("buy").getAsBoolean() ? "BUY " : "SELL ") + PdaClientState.number(row, "remaining") + " "
                    + name(PdaClientState.text(row, "asset"), 9) + " @" + money(PdaClientState.number(row, "price")), id == selectedOrder));
        }
        return result;
    }

    private static List<Level> levels(String key) {
        var result = new ArrayList<Level>();
        for (var element : array(key)) {
            var row = element.getAsJsonObject();
            result.add(new Level("X" + PdaClientState.number(row, "quantity") + "  " + money(PdaClientState.number(row, "price"))));
        }
        return result;
    }

    private static String extreme(boolean high) {
        long best = high ? Long.MIN_VALUE : Long.MAX_VALUE;
        for (var price : array("chart")) best = high ? Math.max(best, price.getAsLong()) : Math.min(best, price.getAsLong());
        return array("chart").isEmpty() ? "" : money(best);
    }

    private static WidgetDef tradeButton(String label, String enabled, Runnable run) {
        return new com.wf.wflib.ui.widget.Button(item -> run.run(), enabled, PdaShell.PANEL, PdaShell.HOVER,
                PdaShell.PRESS, PdaShell.EDGE, 1, 0, 2, 1, 0, 13,
                com.wf.wflib.ui.widget.Point.LEFT, text(label, PdaShell.TEXT));
    }

    public WidgetDef build() {
        UiAction pickItem = item -> { query(((Item) item).id()); };
        UiAction pickOrder = item -> selectedOrder = ((Order) item).id();
        var tree = new ArrayList<WidgetDef>();
        tree.add(at(8, 4, chip("ITEMS", "pda_pl_mkt.tab_items", () -> ordersTab = false)));
        tree.add(at(46, 4, chip("{pda_pl_mkt.orders_label}", "pda_pl_mkt.tab_orders", () -> ordersTab = true)));
        tree.add(when("pda_pl_mkt.tab_items",
                at(8, 20, list("pda_pl_mkt.items", 11, 1, "126", "150", PdaShell.EDGE,
                        PdaWidgets.row("{pda_pl_mkt_item.label}", 114, "pda_pl_mkt_item.selected", pickItem))),
                stack(at(8, 20, list("pda_pl_mkt.orders", 11, 1, "126", "130", PdaShell.EDGE,
                                PdaWidgets.row("{pda_pl_mkt_order.label}", 114, "pda_pl_mkt_order.selected", pickOrder))),
                        at(8, 154, button("CANCEL", "pda_pl_mkt.can_cancel", () -> {
                            if (selectedOrder > 0) PdaClientSetup.send("market", "cancel", Long.toString(selectedOrder));
                            selectedOrder = 0;
                        })),
                        at(54, 154, button("COLLECT", "pda_pl_mkt.can_collect", () -> PdaClientSetup.send("market", "collect"))))));

        tree.add(at(146, 4, text("{pda_pl_mkt.title}", PdaShell.ACCENT)));
        tree.add(at(260, 4, text("{pda_pl_mkt.last}", PdaShell.TEXT)));
        tree.add(at(146, 16, new PriceChart(230, 44, () -> array("chart"), () -> extreme(true), () -> extreme(false))));
        tree.add(at(146, 64, text("SELL ORDERS", PdaShell.DIM)));
        tree.add(at(146, 74, list("pda_pl_mkt.asks", 9, 0, "110", "46", PdaShell.EDGE, text("{pda_pl_mkt_level.label}", PdaShell.TEXT))));
        tree.add(at(262, 64, text("BUY ORDERS", PdaShell.DIM)));
        tree.add(at(262, 74, list("pda_pl_mkt.bids", 9, 0, "110", "46", PdaShell.EDGE, text("{pda_pl_mkt_level.label}", PdaShell.TEXT))));

        tree.add(at(146, 124, button("-", () -> { quantity = Math.max(1, quantity - 1); query(asset()); })));
        tree.add(at(162, 127, text("{pda_pl_mkt.qty}", PdaShell.ACCENT)));
        tree.add(at(196, 124, button("+", () -> { quantity++; query(asset()); })));
        var chips = new ArrayList<WidgetDef>();
        String[] labels = {"1", "8", "64", "1K"};
        for (int i = 0; i < QUANTITIES.length; i++) {
            long value = QUANTITIES[i];
            chips.add(chip(labels[i], "pda_pl_mkt.q" + i, () -> { quantity = value; query(asset()); }));
        }
        tree.add(at(214, 124, row(2, chips.toArray(WidgetDef[]::new))));
        tree.add(at(300, 127, text("{pda_pl_mkt.fee}", PdaShell.DIM)));
        tree.addAll(BAR.widgets(146, 140));
        tree.add(at(146, 158, row(2,
                tradeButton("BUY NOW", "pda_pl_mkt.can_ibuy", () -> trade("instabuy", false)),
                tradeButton("SELL NOW", "pda_pl_mkt.can_isell", () -> trade("instasell", false)),
                tradeButton("BUY ORDER", "pda_pl_mkt.can_buy", () -> trade("buy", true)),
                tradeButton("SELL ORDER", "pda_pl_mkt.can_sell", () -> trade("sell", true)))));
        tree.add(at(146, 175, text("{pda_pl_mkt.quote}", PdaShell.TEXT)));
        tree.add(at(146, 186, text("{pda_pl_mkt.held}", PdaShell.DIM)));
        return stack(tree.toArray(WidgetDef[]::new));
    }
}
