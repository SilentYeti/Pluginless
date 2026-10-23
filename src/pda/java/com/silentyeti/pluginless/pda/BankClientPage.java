package com.silentyeti.pluginless.pda;

import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiAction;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * Bank: one amount bar (stepper, step chips, BAL / DEBT / TYPE) shared by send, request, borrow and repay; people to
 * pay; the loan; and a tab for the latest movements or the incoming request. Nothing here moves money: the buttons
 * send the intent and the server runs the same command as typing it.
 */
final class BankClientPage implements PdaClientPage {
    private static final BankClientPage INSTANCE = new BankClientPage();
    private static final Subject<BankClientPage> BANK = Subject.register("pda_pl_bank", BankClientPage.class, () -> INSTANCE);
    private static final Subject<Person> PERSON = Subject.register("pda_pl_bank_person", Person.class);
    private static final Subject<Tx> TX = Subject.register("pda_pl_bank_tx", Tx.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final long[] STEPS = {100, 1_000, 10_000, 100_000};

    record Person(String name, boolean selected) {}
    record Tx(String clock, String text, String kind) {}

    private static long amount = 1_000;
    private static long step = 1_000;
    private static String target = "";
    private static boolean requestsTab;

    public String id() { return "bank"; }

    public String title() { return "BANK"; }

    private static JsonObject view() { return PdaClientState.view("bank"); }

    private static long debt() { return PdaClientState.number(view(), "debt"); }

    private static long balance() { return PdaClientState.number(view(), "balance"); }

    private static boolean hasRequest() { return view().has("request"); }

    private static JsonObject request() { return hasRequest() ? view().getAsJsonObject("request") : new JsonObject(); }

    private static long fee() {
        var tax = view().has("tax") ? view().get("tax").getAsDouble() : 0;
        return Math.round(amount * tax / 100);
    }

    public void signals() {
        Signals.text(BANK, "amount", 20, b -> PdaClientState.money(view(), amount));
        Signals.text(BANK, "target", 20, b -> target.isEmpty() ? "SELECT A PLAYER" : target);
        Signals.text(BANK, "fee", 40, b -> fee() > 0 ? "FEE " + PdaClientState.money(view(), fee()) : "");
        Signals.text(BANK, "loan", 40, b -> "DEBT " + PdaClientState.money(view(), debt()) + " / " + PdaClientState.money(view(), PdaClientState.number(view(), "limit")));
        Signals.text(BANK, "interest", 24, b -> view().has("interest") ? view().get("interest").getAsDouble() + "% PER DAY" : "");
        Signals.text(BANK, "requests_label", 16, b -> hasRequest() ? "REQUESTS 1" : "REQUESTS");
        Signals.text(BANK, "request_text", 60, b -> hasRequest()
                ? PdaClientState.text(request(), "from") + " ASKS " + PdaClientState.money(view(), PdaClientState.number(request(), "cents"))
                + " (" + PdaClientState.number(request(), "seconds") + "S)" : "NO REQUEST");
        Signals.bool(BANK, "can_send", b -> amount > 0 && !target.isEmpty());
        Signals.bool(BANK, "can_borrow", b -> amount > 0 && debt() == 0 && PdaClientState.number(view(), "limit") > 0);
        Signals.bool(BANK, "can_repay", b -> amount > 0 && debt() > 0);
        Signals.bool(BANK, "has_request", b -> hasRequest());
        Signals.bool(BANK, "tab_history", b -> !requestsTab);
        Signals.bool(BANK, "tab_requests", b -> requestsTab);
        for (int i = 0; i < STEPS.length; i++) {
            long value = STEPS[i];
            Signals.bool(BANK, "step" + i, b -> step == value);
        }
        Signals.items(BANK, "people", b -> people(), PERSON);
        Signals.items(BANK, "history", b -> history(), TX);
        Signals.bool(BANK, "no_history", b -> history().isEmpty());
        Signals.text(PERSON, "name", 16, Person::name);
        Signals.bool(PERSON, "selected", Person::selected);
        Signals.text(TX, "clock", 5, Tx::clock);
        Signals.text(TX, "text", 48, Tx::text);
        Signals.bool(TX, "in", t -> t.kind().equals("in"));
        Signals.bool(TX, "out", t -> t.kind().equals("out"));
        Signals.bool(TX, "debt", t -> t.kind().equals("debt"));
    }

    private static List<Person> people() {
        var result = new ArrayList<Person>();
        if (view().has("people")) for (var name : view().getAsJsonArray("people")) {
            result.add(new Person(name.getAsString(), name.getAsString().equals(target)));
        }
        return result;
    }

    private static List<Tx> history() {
        var result = new ArrayList<Tx>();
        if (!view().has("history")) return result;
        for (var element : view().getAsJsonArray("history")) {
            var row = element.getAsJsonObject();
            var kind = PdaClientState.text(row, "kind");
            var money = PdaClientState.money(view(), PdaClientState.number(row, "cents"));
            var who = PdaClientState.text(row, "who");
            var reason = PdaClientState.text(row, "reason");
            var text = switch (kind) {
                case "in" -> "+" + money + " FROM " + who;
                case "out" -> "-" + money + " TO " + who;
                default -> (reason.equals("debt.loan") ? "LOAN " : reason.equals("debt.interest") ? "INTEREST " : "CHARGE ") + money;
            };
            result.add(new Tx(CLOCK.format(Instant.ofEpochMilli(PdaClientState.number(row, "time"))), text, kind));
        }
        return result;
    }

    private static void adjust(int direction) { amount = Math.max(0, amount + direction * step); }

    private static void type() {
        PdaWidgets.prompt("Amount", "", typed -> {
            long cents = PdaWidgets.cents(typed);
            if (cents >= 0) amount = cents;
        });
    }

    private static void send(String verb) {
        if (!target.isEmpty() && amount > 0) PdaClientSetup.send("bank", verb, target, Long.toString(amount));
    }

    public WidgetDef build() {
        UiAction pick = item -> target = ((Person) item).name();
        WidgetDef steps = stack();
        float x = 92;
        var chips = new ArrayList<WidgetDef>();
        String[] labels = {"$1", "$10", "$100", "$1K"};
        float[] width = {16, 21, 26, 21};
        for (int i = 0; i < STEPS.length; i++) {
            long value = STEPS[i];
            chips.add(at(x, 6, chip(labels[i], "pda_pl_bank.step" + i, () -> step = value)));
            x += width[i] + 3;
        }
        var tree = new ArrayList<WidgetDef>();
        tree.add(at(8, 6, button("-", () -> adjust(-1))));
        tree.add(at(26, 9, text("{pda_pl_bank.amount}", PdaShell.ACCENT)));
        tree.add(at(72, 6, button("+", () -> adjust(1))));
        tree.addAll(chips);
        tree.add(at(x + 4, 6, button("BAL", () -> amount = Math.max(0, balance()))));
        tree.add(at(x + 27, 6, button("DEBT", () -> amount = Math.min(debt(), balance()))));
        tree.add(at(x + 56, 6, button("TYPE", BankClientPage::type)));
        tree.add(at(300, 9, text("{pda_pl_bank.fee}", PdaShell.DIM)));

        tree.add(card(8, 26, 180, 160, "SEND OR REQUEST"));
        tree.add(at(13, 40, text("TO {pda_pl_bank.target}", PdaShell.ACCENT)));
        tree.add(at(13, 52, list("pda_pl_bank.people", 11, 1, "170", "100", PdaShell.EDGE,
                PdaWidgets.row("{pda_pl_bank_person.name}", 160, "pda_pl_bank_person.selected", pick))));
        tree.add(at(13, 164, button("PAY", "pda_pl_bank.can_send", () -> send("pay"))));
        tree.add(at(46, 164, button("REQUEST", "pda_pl_bank.can_send", () -> send("request"))));

        tree.add(card(196, 26, 180, 44, "LOAN"));
        tree.add(at(236, 30, text("{pda_pl_bank.interest}", PdaShell.DIM)));
        tree.add(at(201, 40, text("{pda_pl_bank.loan}", PdaShell.TEXT)));
        tree.add(at(201, 54, button("BORROW", "pda_pl_bank.can_borrow", () -> PdaClientSetup.send("bank", "borrow", Long.toString(amount)))));
        tree.add(at(250, 54, button("REPAY", "pda_pl_bank.can_repay", () -> PdaClientSetup.send("bank", "repay", Long.toString(amount)))));

        tree.add(card(196, 74, 180, 112, ""));
        tree.add(at(201, 78, chip("HISTORY", "pda_pl_bank.tab_history", () -> requestsTab = false)));
        tree.add(at(246, 78, chip("{pda_pl_bank.requests_label}", "pda_pl_bank.tab_requests", () -> requestsTab = true)));
        tree.add(when("pda_pl_bank.tab_history", stack(
                at(201, 95, list("pda_pl_bank.history", 11, 1, "170", "88", PdaShell.EDGE, row(5,
                        text("{pda_pl_bank_tx.clock}", PdaShell.DIM),
                        when("pda_pl_bank_tx.in", text("{pda_pl_bank_tx.text}", PdaShell.ACCENT),
                                when("pda_pl_bank_tx.debt", text("{pda_pl_bank_tx.text}", PdaShell.ALERT),
                                        text("{pda_pl_bank_tx.text}", PdaShell.TEXT)))))),
                when("pda_pl_bank.no_history", at(201, 98, text("NOTHING YET", PdaShell.DIM))))));
        tree.add(when("pda_pl_bank.tab_requests", stack(
                at(201, 100, text("{pda_pl_bank.request_text}", PdaShell.TEXT)),
                when("pda_pl_bank.has_request", stack(
                        at(201, 116, button("ACCEPT", () -> PdaClientSetup.send("bank", "accept"))),
                        at(246, 116, button("DECLINE", () -> PdaClientSetup.send("bank", "decline"))))))));
        return stack(tree.toArray(WidgetDef[]::new));
    }
}
