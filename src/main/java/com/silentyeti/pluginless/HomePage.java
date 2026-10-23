package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;

/** The PDA home: who you are, a live line for each page, and the latest things that happened to you. */
final class HomePage implements PdaPage {
    static final int SHOWN = 6;

    public String id() { return "home"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var id = player.getUUID();
        var view = new JsonObject();
        view.addProperty("rank", RankGate.rankOf(server, id));
        long debt = Bank.debt(server, id);
        view.addProperty("debt", debt);
        var tiles = new JsonObject();
        tiles.addProperty("bank", debt > 0 ? "Debt " + Config.format(debt) : Config.format(Ledger.of(server).balance(id)));
        long listings = Auctions.store(server).value.count(id);
        tiles.addProperty("auction", listings == 0 ? "" : listings + (listings == 1 ? " listing" : " listings"));
        long orders = Market.store(server).value.count(id);
        tiles.addProperty("market", orders == 0 ? "" : orders + (orders == 1 ? " order" : " orders"));
        long portfolio = StockExchange.value(server, id);
        tiles.addProperty("stocks", portfolio == 0 ? "" : Config.format(portfolio));
        long companies = Companies.store(server).value.companies.values().stream()
                .filter(company -> id.equals(company.owner) || company.members.containsKey(id)).count();
        tiles.addProperty("companies", companies == 0 ? "" : companies + (companies == 1 ? " company" : " companies"));
        tiles.addProperty("ranks", RankGate.rankOf(server, id));
        int homes = Teleports.homesOf(server, id).size();
        tiles.addProperty("qol", homes == 0 ? "" : homes + (homes == 1 ? " home" : " homes"));
        tiles.addProperty("cosmetics", "");
        view.add("tiles", tiles);
        var notices = new JsonArray();
        for (var notice : Notices.list(id, SHOWN)) {
            var row = new JsonObject();
            row.addProperty("time", notice.time());
            row.addProperty("kind", notice.kind());
            row.addProperty("text", notice.text());
            row.addProperty("unread", notice.unread());
            notices.add(row);
        }
        view.add("notices", notices);
        return view;
    }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        if (verb.equals("read")) {
            Notices.markRead(player.getUUID());
            return Result.ok("");
        }
        return Result.fail("pluginless.pda.invalid");
    }
}
