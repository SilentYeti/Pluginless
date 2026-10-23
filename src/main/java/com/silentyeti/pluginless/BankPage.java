package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Balance, loan, sending and requesting money, and the latest movements. Every action is the matching command. */
final class BankPage implements PdaPage {
    static final int HISTORY = 20;
    static final int PEOPLE = 40;

    public String id() { return "bank"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var id = player.getUUID();
        var view = new JsonObject();
        long debt = Bank.debt(server, id);
        view.addProperty("debt", debt);
        view.addProperty("limit", Bank.limit(server, id));
        view.addProperty("interest", Config.loanDailyInterest());
        view.addProperty("tax", Config.transferTax());
        var request = Bank.requests(server).get(id, System.currentTimeMillis());
        if (request != null) {
            var pending = new JsonObject();
            pending.addProperty("from", Commands.name(server, request.sender()));
            pending.addProperty("cents", request.value());
            pending.addProperty("seconds", Math.max(0, (request.expires() - System.currentTimeMillis() + 999) / 1000));
            view.add("request", pending);
        }
        var people = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        server.getPlayerList().getPlayers().forEach(online -> { if (!online.getUUID().equals(id)) people.add(online.getScoreboardName()); });
        Players.known(server).forEach((name, known) -> { if (!known.equals(id) && people.size() < PEOPLE) people.add(name); });
        view.add("people", PdaHub.strings(people));
        var rows = new JsonArray();
        var history = Ledger.history(server, id);
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - HISTORY); i--) rows.add(row(server, id, history.get(i)));
        view.add("history", rows);
        return view;
    }

    private static JsonObject row(MinecraftServer server, UUID player, Ledger.Transaction t) {
        var row = new JsonObject();
        boolean obligation = t.reason().equals("debt.loan") || t.reason().startsWith("debt.add.") || t.reason().equals("debt.interest");
        boolean incoming = t.to().equals(player.toString());
        row.addProperty("time", t.time());
        row.addProperty("cents", t.amount());
        // kind: in, out, or debt (an obligation, not a movement of money)
        row.addProperty("kind", obligation ? "debt" : incoming ? "in" : "out");
        row.addProperty("who", obligation ? "" : Bank.endpoint(server, incoming ? t.from() : t.to()));
        row.addProperty("reason", t.reason());
        return row;
    }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        switch (verb) {
            case "pay", "request" -> {
                if (args.size() != 2) return Result.fail("pluginless.pda.invalid");
                var name = PdaCommands.name(args.get(0));
                var amount = PdaCommands.amount(args.get(1));
                if (name == null || amount == null) return Result.fail("pluginless.pda.invalid");
                return PdaCommands.run(player, verb.equals("pay") ? "pay " + name + " " + amount : "bank request " + name + " " + amount);
            }
            case "borrow", "repay" -> {
                var amount = args.size() == 1 ? PdaCommands.amount(args.get(0)) : null;
                return amount == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "bank " + verb + " " + amount);
            }
            case "accept", "decline" -> {
                return PdaCommands.run(player, "bank " + verb);
            }
            default -> {
                return Result.fail("pluginless.pda.invalid");
            }
        }
    }
}
