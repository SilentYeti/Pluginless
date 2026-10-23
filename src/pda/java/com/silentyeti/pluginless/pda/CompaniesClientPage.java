package com.silentyeti.pluginless.pda;

import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.pda.client.TextPromptScreen;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiAction;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.wf.wflib.ui.Ui.*;

/** Company and shop panels; all prices, permissions and transactions come back from the server. */
final class CompaniesClientPage implements PdaClientPage {
    record Entry(String id, String name, String line) {}
    record Slot(int slot, String item, String label) {}
    private static final Subject<Entry> ENTRY = Subject.register("pda_pl_company_entry", Entry.class);
    private static final Subject<Entry> MEMBER = Subject.register("pda_pl_company_member", Entry.class);
    private static final Subject<Entry> OFFER = Subject.register("pda_pl_shop_offer", Entry.class);
    private static final Subject<Entry> HISTORY = Subject.register("pda_pl_shop_history", Entry.class);
    private static final Subject<Slot> SLOT = Subject.register("pda_pl_shop_slot", Slot.class);
    private static String amount = "100", target = "", role = "employee", item = "", quantity = "1";
    private static String sellPrice = "0", buyPrice = "0", limit = "0";
    private static int inventorySlot = -1;

    public String id() { return "companies"; }
    public String title() { return "COMPANIES & SHOPS"; }

    private static JsonObject view() { return PdaClientState.view("companies"); }
    private static JsonObject detail(String key) { return view().has(key) ? view().getAsJsonObject(key) : new JsonObject(); }
    private static String str(JsonObject object, String key) { return PdaClientState.text(object, key); }
    private static long num(JsonObject object, String key) { return PdaClientState.number(object, key); }
    private static boolean flag(JsonObject object, String key) { return object.has(key) && object.get(key).getAsBoolean(); }
    private static String money(long cents) { return PdaClientState.money(view(), cents); }
    private static String company() { return str(detail("company"), "name"); }
    private static String shop() { return str(detail("shop"), "name"); }
    private static void send(String verb, String... args) { PdaClientSetup.send("companies", verb, args); }
    private static void prompt(String label, String initial, Consumer<String> done) {
        TextPromptScreen.open(Component.literal(label), 64, initial, done);
    }

    public void signals() {
        Signals.bool(PlApp.APP, "co_shops", a -> str(view(), "tab").equals("shops"));
        Signals.bool(PlApp.APP, "co_company", a -> !company().isEmpty());
        Signals.bool(PlApp.APP, "co_shop", a -> !shop().isEmpty());
        for (String key : List.of("manage", "spend", "own", "member"))
            Signals.bool(PlApp.APP, "co_" + key, a -> flag(detail("company"), key));
        for (String key : List.of("edit", "stock"))
            Signals.bool(PlApp.APP, "co_" + key, a -> flag(detail("shop"), key));
        Signals.text(PlApp.APP, "co_page", 16, a -> num(view(), "page") + "/" + num(view(), "pages"));
        Signals.text(PlApp.APP, "co_tab", 12, a -> str(view(), "tab").toUpperCase());
        Signals.text(PlApp.APP, "co_search", 17, a -> str(view(), "search"));
        Signals.text(PlApp.APP, "co_fee", 24, a -> "FOUNDING: " + money(num(view(), "fee")));
        Signals.text(PlApp.APP, "co_name", 24, a -> company());
        Signals.text(PlApp.APP, "co_owner", 36, a -> "OWNER: " + str(detail("company"), "owner"));
        Signals.text(PlApp.APP, "co_balance", 36, a -> "ACCOUNT: " + money(num(detail("company"), "balance")));
        Signals.text(PlApp.APP, "co_payroll", 40, a -> {
            var c = detail("company");
            return "PAYROLL " + money(num(c, "wages")) + " " + str(c, "payroll");
        });
        Signals.text(PlApp.APP, "co_next", 32, a -> "NEXT: " + num(detail("company"), "next") / 60 + " MIN");
        Signals.text(PlApp.APP, "co_members_page", 24, a -> "MEMBERS " + num(detail("company"), "memberPage") + "/" + num(detail("company"), "memberPages"));
        Signals.text(PlApp.APP, "co_amount", 24, a -> "AMOUNT: " + amount);
        Signals.text(PlApp.APP, "co_target", 30, a -> "PLAYER: " + target);
        Signals.text(PlApp.APP, "co_role", 16, a -> (flag(detail("company"), "own") ? role : "employee").toUpperCase());
        Signals.text(PlApp.APP, "co_shop_name", 24, a -> shop());
        Signals.text(PlApp.APP, "co_shop_owner", 36, a -> "OWNER: " + str(detail("shop"), "owner"));
        Signals.text(PlApp.APP, "co_shop_account", 40, a -> "COMPANY ACCOUNT: " + str(detail("shop"), "earnings"));
        Signals.text(PlApp.APP, "co_offers_page", 24, a -> "OFFERS " + num(detail("shop"), "offerPage") + "/" + num(detail("shop"), "offerPages"));
        Signals.text(PlApp.APP, "co_item", 38, a -> item);
        Signals.text(PlApp.APP, "co_quantity", 24, a -> "QTY: " + quantity);
        Signals.text(PlApp.APP, "co_prices", 42, a -> "SELL " + sellPrice + " / BUY " + buyPrice + " / LIMIT " + limit);
        Signals.items(PlApp.APP, "co_rows", a -> entries(), ENTRY);
        Signals.items(PlApp.APP, "co_members", a -> members(), MEMBER);
        Signals.items(PlApp.APP, "co_offers", a -> offers(), OFFER);
        Signals.items(PlApp.APP, "co_inventory", a -> inventory(), SLOT);
        Signals.items(PlApp.APP, "co_history", a -> array(detail("shop"), "history").stream()
                .map(r -> new Entry("", money(num(r, "amount")), str(r, "reason"))).toList(), HISTORY);
        for (var subject : List.of(ENTRY, MEMBER, OFFER, HISTORY)) {
            Signals.text(subject, "name", 36, Entry::name);
            Signals.text(subject, "line", 42, Entry::line);
        }
        Signals.text(SLOT, "label", 16, Slot::label);
    }

    private static List<JsonObject> array(JsonObject parent, String key) {
        var result = new ArrayList<JsonObject>();
        if (parent.has(key)) for (var element : parent.getAsJsonArray(key)) result.add(element.getAsJsonObject());
        return result;
    }
    private static List<Entry> entries() {
        return array(view(), "rows").stream().map(r -> new Entry(Long.toString(num(r, "id")), str(r, "name"), str(r, "line"))).toList();
    }
    private static List<Entry> members() {
        return array(detail("company"), "members").stream().map(r -> new Entry(str(r, "name"), str(r, "name"),
                str(r, "role").toUpperCase() + " " + money(num(r, "wage")) + "/PAYDAY")).toList();
    }
    private static List<Entry> offers() {
        boolean admin = flag(detail("shop"), "admin");
        return array(detail("shop"), "offers").stream().map(r -> new Entry(str(r, "item"), str(r, "item"),
                "BUY " + (num(r, "sell") == 0 ? "-" : money(num(r, "sell"))) + " / SELL "
                        + (num(r, "buy") == 0 ? "-" : money(num(r, "buy"))) + " / "
                        + (admin ? "UNLIMITED" : "STOCK " + num(r, "stock") + " LIMIT " + num(r, "limit")))).toList();
    }
    private static List<Slot> inventory() {
        var result = new ArrayList<Slot>();
        var player = Minecraft.getInstance().player;
        if (player != null) for (int i = 0; i < 36; i++) {
            var stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) result.add(new Slot(i, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getHoverName().getString() + " x" + stack.getCount()));
        }
        return result;
    }

    private static WidgetDef button(String label, UiAction action) { return button(label, "true", action); }
    private static WidgetDef button(String label, String enabled, UiAction action) {
        return new Button(action, enabled, PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 4, 3, 0, 0, Point.TOP_LEFT, text(label, PdaShell.TEXT));
    }
    private static WidgetDef line(String signal) { return text("{pda_pl." + signal + "}", PdaShell.DIM); }
    private static WidgetDef entryButton(String subject, UiAction action) {
        return new Button(action, "true", PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 4, 3, 240, 28, Point.TOP_LEFT, column(3,
                text("{" + subject + ".name}", PdaShell.TEXT), text("{" + subject + ".line}", PdaShell.DIM)));
    }

    public WidgetDef build() {
        WidgetDef entries = list("pda_pl.co_rows", 29, 2, "110", "124", PdaShell.ACCENT,
                new Button(e -> { item = ""; target = ""; inventorySlot = -1; send("select", ((Entry) e).id()); }, "true",
                        PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE, 1, 0, 3, 3, 106, 29, Point.TOP_LEFT,
                        column(3, text("{pda_pl_company_entry.name}", PdaShell.TEXT), text("{pda_pl_company_entry.line}", PdaShell.DIM))));
        WidgetDef members = repeat("pda_pl.co_members", true, 3,
                entryButton("pda_pl_company_member", e -> target = ((Entry) e).id()));
        WidgetDef offers = repeat("pda_pl.co_offers", true, 3,
                entryButton("pda_pl_shop_offer", e -> { item = ((Entry) e).id(); inventorySlot = -1; }));
        WidgetDef companyPanel = column(5,
                text("{pda_pl.co_name}", PdaShell.ACCENT), line("co_owner"), line("co_balance"),
                line("co_payroll"), line("co_next"),
                row(4, button("{pda_pl.co_amount}", e -> prompt("Amount in currency units (wage 0 clears)", amount, v -> amount = v))),
                row(4, button("DEPOSIT", e -> send("deposit", company(), amount)),
                        button("WITHDRAW", "pda_pl.co_spend", e -> send("withdraw", company(), amount))),
                line("co_members_page"), members,
                row(4, button("< MEMBERS", e -> send("members", "-1")), button("MEMBERS >", e -> send("members", "1"))),
                button("{pda_pl.co_target}", e -> prompt("Player name", target, v -> target = v)),
                when("pda_pl.co_manage", column(4,
                        row(4, button("{pda_pl.co_role}", "pda_pl.co_own", e -> role = role.equals("employee") ? "manager" : "employee"),
                                button("HIRE / SET ROLE", e -> send("hire", company(), target, flag(detail("company"), "own") ? role : "employee"))),
                        row(4, button("SET WAGE", e -> send("wage", company(), target, amount)),
                                button("FIRE", e -> send("fire", company(), target))),
                        button("OPEN SHOP", e -> { String c = company(); prompt("New shop name (3-24 letters, digits, _)", "", v -> send("shop_open", c, v)); }))),
                when("pda_pl.co_member", button("LEAVE", e -> send("leave", company()))),
                when("pda_pl.co_own", button("DISSOLVE...", e -> {
                    String c = company(); prompt("Type " + c + " to dissolve and return stock", "", v -> { if (v.equals(c)) send("dissolve", c); });
                })));
        WidgetDef shopPanel = column(5,
                text("{pda_pl.co_shop_name}", PdaShell.ACCENT), line("co_shop_owner"), line("co_offers_page"), offers,
                row(4, button("< OFFERS", e -> send("offers", "-1")), button("OFFERS >", e -> send("offers", "1"))),
                text("SELECTED: {pda_pl.co_item}", PdaShell.ACCENT),
                row(4, button("{pda_pl.co_quantity}", e -> prompt("Quantity", quantity, v -> quantity = v)),
                        button("x1", e -> quantity = "1"), button("x16", e -> quantity = "16"), button("x64", e -> quantity = "64")),
                row(4, button("BUY", e -> send("shop_buy", shop(), item, quantity)),
                        button("SELL", e -> send("shop_sell", shop(), item, quantity))),
                when("pda_pl.co_stock", column(4,
                        line("co_shop_account"), text("LATEST COMPANY TRANSACTIONS", PdaShell.DIM),
                        repeat("pda_pl.co_history", true, 3, column(2,
                                text("{pda_pl_shop_history.name}", PdaShell.ACCENT), text("{pda_pl_shop_history.line}", PdaShell.DIM))),
                        text("RESTOCK: PICK YOUR INVENTORY STACK", PdaShell.DIM),
                        grid("pda_pl.co_inventory", 2, 3, button("{pda_pl_shop_slot.label}", e -> {
                            var slot = (Slot) e; item = slot.item(); inventorySlot = slot.slot();
                        })),
                        row(4, button("RESTOCK", e -> PdaClientSetup.send("companies", "shop_stock", List.of(shop(), item, quantity), inventorySlot)),
                                button("UNSTOCK", e -> send("shop_unstock", shop(), item, quantity))))),
                when("pda_pl.co_edit", column(4,
                        text("OFFER PRICES (0 DISABLES THAT SIDE)", PdaShell.DIM), line("co_prices"),
                        row(4, button("ITEM ID", e -> prompt("Item registry ID", item, v -> { item = v; inventorySlot = -1; })),
                                button("SELL PRICE", e -> prompt("Price customers pay", sellPrice, v -> sellPrice = v))),
                        row(4, button("BUY PRICE", e -> prompt("Price shop pays customers", buyPrice, v -> buyPrice = v)),
                                button("BUY LIMIT", e -> prompt("Quantity shop will buy", limit, v -> limit = v))),
                        button("SAVE OFFER", e -> send("shop_offer", shop(), item, sellPrice, buyPrice, limit)),
                        button("CLOSE SHOP...", e -> { String s = shop(); prompt("Type " + s + " to close and return stock", "", v -> { if (v.equals(s)) send("shop_close", s); }); }))));
        return stack(
                at("8", "3", row(4, button("MY COMPANY", e -> send("tab", "mine")),
                        button("DIRECTORY", e -> send("tab", "directory")), button("SHOPS", e -> send("tab", "shops")),
                        button("SEARCH", e -> prompt("Search companies, shops or items (use * to clear)", str(view(), "search"), v -> send("search", v.equals("*") ? "" : v))))),
                at("8", "25", entries),
                at("8", "152", row(5, button("<", e -> send("page", "-1")), line("co_page"), button(">", e -> send("page", "1")))),
                at("8", "174", button("FOUND COMPANY", e -> prompt("New company name; fee " + money(num(view(), "fee")), "", v -> send("create", v)))),
                at("126", "25", when("pda_pl.co_shops",
                        when("pda_pl.co_shop", scroll("250", "162", PdaShell.ACCENT, shopPanel), text("NO SHOPS YET", PdaShell.DIM)),
                        when("pda_pl.co_company", scroll("250", "162", PdaShell.ACCENT, companyPanel),
                                column(6, text("NO COMPANY SELECTED", PdaShell.DIM), line("co_fee"))))));
    }
}
