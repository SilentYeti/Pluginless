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
import static com.silentyeti.pluginless.pda.PdaWidgets.chip;
import static com.wf.wflib.ui.Ui.bar;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.row;
import static com.wf.wflib.ui.Ui.stack;
import static com.silentyeti.pluginless.pda.PdaWidgets.fit;
import static com.silentyeti.pluginless.pda.PdaWidgets.text;
import static com.silentyeti.pluginless.pda.PdaWidgets.wrap;
import static com.wf.wflib.ui.Ui.when;

/**
 * Stocks: MARKET (prices, chart, buy and sell shares), MARGIN (borrow against holdings, with a health bar) and SHORTS
 * (open and close). Buy, sell, borrow and short all run the matching command, so limits, halts and rank rules stay
 * the server's.
 */
final class StocksClientPage implements PdaClientPage {
    private static final StocksClientPage INSTANCE = new StocksClientPage();
    private static final Subject<StocksClientPage> STK = Subject.register("pda_pl_stk", StocksClientPage.class, () -> INSTANCE);
    private static final Subject<Stock> STOCK = Subject.register("pda_pl_stk_stock", Stock.class);
    private static final Subject<Position> POSITION = Subject.register("pda_pl_stk_pos", Position.class);
    private static final Subject<Firm> FIRM = Subject.register("pda_pl_stk_firm", Firm.class);
    private static final Subject<Order> ORDER = Subject.register("pda_pl_stk_order", Order.class);
    private static final AmountBar BAR = new AmountBar("pda_pl_stk", StocksClientPage::view, 100_000, 100, 1_000, 10_000, 100_000);
    private static final CountBar COUNT = new CountBar("pda_pl_stk", () -> { }, 1, 10, 100, 1_000);

    record Stock(String ticker, String label, boolean selected) {}
    record Position(String ticker, String label, boolean selected) {}
    record Firm(String name, String label, boolean selected) {}
    record Order(String id, String label) {}

    private static int tab; // 0 market, 1 margin, 2 shorts, 3 shares
    private static String selectedPosition = "";
    private static String company = "";
    private static boolean opened;

    public String id() { return "stocks"; }

    public String title() { return "STOCKS"; }

    private static JsonObject view() { return PdaClientState.view("stocks"); }

    private static JsonArray array(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray(); }

    private static JsonObject object(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject(); }

    private static long number(String key) { return PdaClientState.number(view(), key); }

    private static String ticker() { return PdaClientState.text(view(), "ticker"); }

    private static String money(long cents) { return PdaClientState.money(view(), cents); }

    private static double decimal(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsDouble() : 0; }

    private static long holdings() { return number("portfolio"); }

    private static double health() {
        long loan = PdaClientState.number(object(view(), "margin"), "loan");
        return loan <= 0 ? 0 : (double) holdings() / loan;
    }

    private static long heldOf(String ticker) {
        for (var element : array(view(), "stocks")) if (PdaClientState.text(element.getAsJsonObject(), "ticker").equals(ticker)) return PdaClientState.number(element.getAsJsonObject(), "held");
        return 0;
    }

    private static boolean frozen() {
        for (var element : array(view(), "stocks")) {
            var row = element.getAsJsonObject();
            if (PdaClientState.text(row, "ticker").equals(ticker())) return row.has("frozen") && row.get("frozen").getAsBoolean();
        }
        return false;
    }

    public void signals() {
        BAR.signals(STK);
        COUNT.signals(STK);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            if (!PdaClientState.page().equals("stocks")) { opened = false; return; }
            if (!opened) opened = true;
        });
        Signals.bool(STK, "tab_market", s -> tab == 0);
        Signals.bool(STK, "tab_margin", s -> tab == 1);
        Signals.bool(STK, "tab_shorts", s -> tab == 2);
        Signals.bool(STK, "tab_shares", s -> tab == 3);
        Signals.text(STK, "portfolio", PdaWidgets.TEXT_MAX, s -> "HOLDINGS " + money(holdings()));
        Signals.items(STK, "stocks", s -> stocks(), STOCK);
        Signals.items(STK, "positions", s -> positions(), POSITION);
        Signals.bool(STK, "no_positions", s -> positions().isEmpty());
        Signals.text(STK, "title", PdaWidgets.TEXT_MAX, s -> ticker().isEmpty() ? "NO STOCKS" : ticker() + "  " + stockName());
        Signals.text(STK, "about", PdaWidgets.TEXT_MAX, s -> PdaClientState.text(view(), "description"));
        Signals.text(STK, "quote", PdaWidgets.TEXT_MAX, s -> "BUY " + money(number("buy")) + "  SELL " + money(number("sell")) + (frozen() ? "  HALTED" : ""));
        Signals.text(STK, "held", PdaWidgets.TEXT_MAX, s -> "YOU HOLD " + heldOf(ticker()) + " OF " + number("limit") + "   COST " + money(number("buy") * COUNT.count));
        Signals.text(STK, "last", PdaWidgets.TEXT_MAX, s -> {
            var chart = array(view(), "chart");
            return chart.isEmpty() ? "" : "NOW " + money(chart.get(chart.size() - 1).getAsLong());
        });
        Signals.bool(STK, "can_buy", s -> !ticker().isEmpty() && !frozen() && heldOf(ticker()) + COUNT.count <= number("limit"));
        Signals.bool(STK, "can_sell", s -> !ticker().isEmpty() && !frozen() && heldOf(ticker()) >= COUNT.count);
        // margin
        Signals.text(STK, "m_summary", PdaWidgets.TEXT_MAX, s -> {
            var m = object(view(), "margin");
            return "HOLDINGS " + money(holdings()) + "  LOAN " + money(PdaClientState.number(m, "loan")) + "  MAX " + money(PdaClientState.number(m, "limit"));
        });
        Signals.text(STK, "m_rules", PdaWidgets.TEXT_MAX, s -> {
            var m = object(view(), "margin");
            return decimal(m, "multiple") + "X  INTEREST " + decimal(m, "interest") + "%/DAY  WARN " + decimal(m, "warn") + "X  SELL-OFF " + decimal(m, "liquidate") + "X";
        });
        Signals.text(STK, "m_health", PdaWidgets.TEXT_MAX, s -> PdaClientState.number(object(view(), "margin"), "loan") <= 0 ? "NO LOAN" : String.format("HEALTH %.2fX", health()));
        Signals.num(STK, "health", s -> Math.max(0, Math.min(1, health() - 1)));
        Signals.bool(STK, "h_ok", s -> health() == 0 || health() >= decimal(object(view(), "margin"), "warn"));
        Signals.bool(STK, "h_warn", s -> health() > 0 && health() < decimal(object(view(), "margin"), "warn") && health() >= decimal(object(view(), "margin"), "liquidate"));
        Signals.bool(STK, "h_bad", s -> health() > 0 && health() < decimal(object(view(), "margin"), "liquidate"));
        Signals.bool(STK, "can_borrow", s -> BAR.amount > 0 && PdaClientState.number(object(view(), "margin"), "limit") > PdaClientState.number(object(view(), "margin"), "loan"));
        Signals.bool(STK, "can_repay", s -> BAR.amount > 0 && PdaClientState.number(object(view(), "margin"), "loan") > 0);
        // shorts
        Signals.text(STK, "s_rules", PdaWidgets.TEXT_MAX, s -> {
            var sh = object(view(), "shorts");
            return "DEPOSIT " + Math.round(decimal(sh, "deposit") * 100) + "%  CLOSED AT " + Math.round(decimal(sh, "close") * 100) + "% LOST  FEE " + decimal(sh, "fee") + "%/DAY";
        });
        Signals.text(STK, "s_new", PdaWidgets.TEXT_MAX, s -> "SHORT " + ticker() + " X" + COUNT.count + "  DEPOSIT ABOUT "
                + money(Math.round(decimal(object(view(), "shorts"), "deposit") * number("sell") * COUNT.count)));
        Signals.bool(STK, "can_open", s -> !ticker().isEmpty() && !frozen());
        Signals.bool(STK, "can_close", s -> !selectedPosition.isEmpty());
        // company shares
        Signals.items(STK, "firms", s -> firms(), FIRM);
        Signals.items(STK, "orders", s -> orders(), ORDER);
        Signals.bool(STK, "no_firms", s -> firms().isEmpty());
        Signals.bool(STK, "no_orders", s -> orders().isEmpty());
        Signals.text(STK, "c_title", PdaWidgets.TEXT_MAX, s -> company.isEmpty() ? "SELECT A COMPANY" : company);
        Signals.text(STK, "c_held", PdaWidgets.TEXT_MAX, s -> company.isEmpty() ? "" : "YOU HOLD " + PdaClientState.number(firm(), "held") + " OF " + PdaClientState.number(firm(), "outstanding")
                + " SHARES, VOTES " + PdaClientState.number(firm(), "votes"));
        Signals.text(STK, "c_book", PdaWidgets.TEXT_MAX, s -> company.isEmpty() ? "" : "ASK " + level("ask") + "  BID " + level("bid"));
        Signals.text(STK, "c_total", PdaWidgets.TEXT_MAX, s -> "PRICE X COUNT " + money(BAR.amount * COUNT.count));
        Signals.bool(STK, "c_ready", s -> !company.isEmpty() && BAR.amount > 0);
        Signals.bool(STK, "c_takeover", s -> !company.isEmpty() && !flag(firm(), "owner")
                && PdaClientState.number(firm(), "votes") * 2 > PdaClientState.number(firm(), "outstanding"));
        Signals.bool(STK, "c_owner", s -> !company.isEmpty() && BAR.amount > 0 && flag(firm(), "owner"));
        Signals.text(FIRM, "label", PdaWidgets.TEXT_MAX, Firm::label);
        Signals.bool(FIRM, "selected", Firm::selected);
        Signals.text(ORDER, "label", PdaWidgets.TEXT_MAX, Order::label);
        Signals.text(STOCK, "label", PdaWidgets.TEXT_MAX, Stock::label);
        Signals.bool(STOCK, "selected", Stock::selected);
        Signals.text(POSITION, "label", PdaWidgets.TEXT_MAX, Position::label);
        Signals.bool(POSITION, "selected", Position::selected);
    }

    private static JsonObject shares() { return object(view(), "shares"); }

    private static boolean flag(JsonObject o, String key) { return o.has(key) && o.get(key).getAsBoolean(); }

    private static JsonObject firm() {
        for (var element : array(shares(), "companies")) {
            var row = element.getAsJsonObject();
            if (PdaClientState.text(row, "name").equals(company)) return row;
        }
        return new JsonObject();
    }

    private static String level(String side) {
        long price = PdaClientState.number(firm(), side);
        return price == 0 ? "-" : money(price) + " X" + PdaClientState.number(firm(), side + "Quantity");
    }

    private static List<Firm> firms() {
        var result = new ArrayList<Firm>();
        for (var element : array(shares(), "companies")) {
            var row = element.getAsJsonObject();
            var name = PdaClientState.text(row, "name");
            long held = PdaClientState.number(row, "held");
            result.add(new Firm(name, name + (held > 0 ? " X" + held : ""), name.equals(company)));
        }
        return result;
    }

    private static List<Order> orders() {
        var result = new ArrayList<Order>();
        for (var element : array(shares(), "orders")) {
            var row = element.getAsJsonObject();
            result.add(new Order(Long.toString(PdaClientState.number(row, "id")), (flag(row, "buy") ? "BUY " : "SELL ") + PdaClientState.number(row, "quantity") + " "
                    + PdaClientState.text(row, "company") + " @" + money(PdaClientState.number(row, "price"))));
        }
        return result;
    }

    private static String stockName() {
        for (var element : array(view(), "stocks")) {
            var row = element.getAsJsonObject();
            if (PdaClientState.text(row, "ticker").equals(ticker())) return PdaClientState.text(row, "name");
        }
        return "";
    }

    private static List<Stock> stocks() {
        var result = new ArrayList<Stock>();
        for (var element : array(view(), "stocks")) {
            var row = element.getAsJsonObject();
            var t = PdaClientState.text(row, "ticker");
            double move = decimal(row, "move");
            long held = PdaClientState.number(row, "held");
            result.add(new Stock(t, t + " " + money(PdaClientState.number(row, "price")) + (move == 0 ? "" : String.format(" %+.1f%%", move * 100 / Math.max(1, PdaClientState.number(row, "price") / 100.0)))
                    + (held > 0 ? " X" + held : ""), t.equals(ticker())));
        }
        return result;
    }

    private static List<Position> positions() {
        var result = new ArrayList<Position>();
        for (var element : array(object(view(), "shorts"), "positions")) {
            var row = element.getAsJsonObject();
            var t = PdaClientState.text(row, "ticker");
            long pnl = PdaClientState.number(row, "pnl");
            result.add(new Position(t, t + " X" + PdaClientState.number(row, "shares") + " @" + money(PdaClientState.number(row, "entry"))
                    + "  DEP " + money(PdaClientState.number(row, "deposit")) + "  " + (pnl < 0 ? "-" : "+") + money(Math.abs(pnl)), t.equals(selectedPosition)));
        }
        return result;
    }

    public WidgetDef build() {
        UiAction pick = item -> PdaClientSetup.send("stocks", "select", ((Stock) item).ticker());
        UiAction pickPosition = item -> selectedPosition = ((Position) item).ticker();
        var tree = new ArrayList<WidgetDef>();
        tree.add(at(8, 4, row(2,
                chip("MARKET", "pda_pl_stk.tab_market", () -> tab = 0),
                chip("MARGIN", "pda_pl_stk.tab_margin", () -> tab = 1),
                chip("SHORTS", "pda_pl_stk.tab_shorts", () -> tab = 2),
                chip("SHARES", "pda_pl_stk.tab_shares", () -> tab = 3))));
        tree.add(at(250, 7, text("{pda_pl_stk.portfolio}", PdaShell.DIM)));

        tree.add(when("pda_pl_stk.tab_market", stack(
                at(8, 20, list("pda_pl_stk.stocks", 11, 1, "150", "150", PdaShell.EDGE,
                        PdaWidgets.row("{pda_pl_stk_stock.label}", 138, "pda_pl_stk_stock.selected", pick))),
                at(166, 20, fit("{pda_pl_stk.title}", PdaShell.ACCENT, 210)),
                at(166, 32, new PriceChart(210, 44, () -> array(view(), "chart"), () -> extreme(true), () -> extreme(false))),
                at(166, 80, fit("{pda_pl_stk.quote}", PdaShell.TEXT, 210)),
                at(166, 92, fit("{pda_pl_stk.held}", PdaShell.DIM, 210)),
                at(166, 150, wrap("{pda_pl_stk.about}", PdaShell.DIM, 210, 4)),
                stack(COUNT.widgets(166, 110).toArray(WidgetDef[]::new)),
                at(166, 128, row(2,
                        button("BUY", "pda_pl_stk.can_buy", () -> PdaClientSetup.send("stocks", "buy", ticker(), Long.toString(COUNT.count))),
                        button("SELL", "pda_pl_stk.can_sell", () -> PdaClientSetup.send("stocks", "sell", ticker(), Long.toString(COUNT.count))))))));

        tree.add(when("pda_pl_stk.tab_margin", stack(
                at(8, 24, fit("{pda_pl_stk.m_summary}", PdaShell.TEXT, 368)),
                at(8, 36, fit("{pda_pl_stk.m_rules}", PdaShell.DIM, 368)),
                at(8, 56, text("{pda_pl_stk.m_health}", PdaShell.TEXT)),
                when("pda_pl_stk.h_ok", at(8, 68, bar(200, 10, "pda_pl_stk.health", PdaShell.ACCENT, PdaShell.BAR_BACK))),
                when("pda_pl_stk.h_warn", at(8, 68, bar(200, 10, "pda_pl_stk.health", "#ffd0a040", PdaShell.BAR_BACK))),
                when("pda_pl_stk.h_bad", at(8, 68, bar(200, 10, "pda_pl_stk.health", PdaShell.ALERT, PdaShell.BAR_BACK))),
                at(8, 110, button("BORROW", "pda_pl_stk.can_borrow", () -> PdaClientSetup.send("stocks", "borrow", Long.toString(BAR.amount)))),
                at(56, 110, button("REPAY", "pda_pl_stk.can_repay", () -> PdaClientSetup.send("stocks", "repay", Long.toString(BAR.amount)))))));

        tree.add(when("pda_pl_stk.tab_shorts", stack(
                at(8, 20, fit("{pda_pl_stk.s_rules}", PdaShell.DIM, 368)),
                at(8, 34, list("pda_pl_stk.positions", 11, 1, "368", "70", PdaShell.EDGE,
                        PdaWidgets.row("{pda_pl_stk_pos.label}", 356, "pda_pl_stk_pos.selected", pickPosition))),
                when("pda_pl_stk.no_positions", at(12, 38, text("NO OPEN SHORTS", PdaShell.DIM))),
                at(8, 110, button("CLOSE SELECTED", "pda_pl_stk.can_close", () -> {
                    if (!selectedPosition.isEmpty()) PdaClientSetup.send("stocks", "close", selectedPosition);
                })),
                at(8, 132, fit("{pda_pl_stk.s_new}", PdaShell.TEXT, 368)),
                stack(COUNT.widgets(8, 146).toArray(WidgetDef[]::new)),
                at(176, 146, button("OPEN SHORT", "pda_pl_stk.can_open", () -> PdaClientSetup.send("stocks", "open", ticker(), Long.toString(COUNT.count)))))));

        UiAction pickFirm = item -> company = ((Firm) item).name();
        UiAction cancel = item -> PdaClientSetup.send("stocks", "scancel", ((Order) item).id());
        tree.add(when("pda_pl_stk.tab_shares", stack(
                at(8, 20, list("pda_pl_stk.firms", 11, 1, "150", "72", PdaShell.EDGE,
                        PdaWidgets.row("{pda_pl_stk_firm.label}", 138, "pda_pl_stk_firm.selected", pickFirm))),
                when("pda_pl_stk.no_firms", at(12, 24, text("NO COMPANY HAS SHARES", PdaShell.DIM))),
                at(166, 20, text("{pda_pl_stk.c_title}", PdaShell.ACCENT)),
                at(166, 32, text("{pda_pl_stk.c_held}", PdaShell.TEXT)),
                at(166, 44, text("{pda_pl_stk.c_book}", PdaShell.TEXT)),
                at(166, 56, text("{pda_pl_stk.c_total}", PdaShell.DIM)),
                at(166, 70, button("TAKE OVER", "pda_pl_stk.c_takeover", () -> PdaClientSetup.send("stocks", "takeover", company))),
                at(8, 98, text("PRICE PER SHARE", PdaShell.DIM)),
                stack(BAR.widgets(8, 108, new AmountBar.Shortcut("ASK", 22, () -> PdaClientState.number(firm(), "ask")),
                        new AmountBar.Shortcut("BID", 22, () -> PdaClientState.number(firm(), "bid"))).toArray(WidgetDef[]::new)),
                stack(COUNT.widgets(8, 128).toArray(WidgetDef[]::new)),
                at(176, 128, button("BUY", "pda_pl_stk.c_ready", () -> PdaClientSetup.send("stocks", "sbuy", company, Long.toString(COUNT.count), Long.toString(BAR.amount)))),
                at(206, 128, button("SELL", "pda_pl_stk.c_ready", () -> PdaClientSetup.send("stocks", "ssell", company, Long.toString(COUNT.count), Long.toString(BAR.amount)))),
                at(240, 128, button("DIVIDEND", "pda_pl_stk.c_owner", () -> PdaClientSetup.send("stocks", "dividend", company, Long.toString(BAR.amount)))),
                at(8, 146, text("YOUR ORDERS (CLICK TO CANCEL)", PdaShell.DIM)),
                when("pda_pl_stk.no_orders", at(12, 158, text("NONE", PdaShell.DIM)),
                        at(8, 158, list("pda_pl_stk.orders", 11, 1, "368", "32", PdaShell.EDGE,
                                PdaWidgets.row("{pda_pl_stk_order.label}", 356, "false", cancel)))))));

        tree.add(when("pda_pl_stk.tab_margin", stack(BAR.widgets(8, 90).toArray(WidgetDef[]::new))));
        return stack(tree.toArray(WidgetDef[]::new));
    }

    private static String extreme(boolean high) {
        var chart = array(view(), "chart");
        if (chart.isEmpty()) return "";
        long best = high ? Long.MIN_VALUE : Long.MAX_VALUE;
        for (var price : chart) best = high ? Math.max(best, price.getAsLong()) : Math.min(best, price.getAsLong());
        return money(best);
    }
}
