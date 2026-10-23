package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The admin stocks with the two ways to leverage them: margin (borrow to hold more) and short selling. */
final class StocksPage implements PdaPage {
    static final int CHART = 40;

    private static final Map<UUID, String> SELECTED = new HashMap<>();

    public String id() { return "stocks"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var id = player.getUUID();
        var config = StockExchange.config;
        String chosen;
        synchronized (SELECTED) { chosen = SELECTED.getOrDefault(id, ""); }
        if (!config.stocks.containsKey(chosen)) chosen = config.stocks.isEmpty() ? "" : config.stocks.keySet().iterator().next();
        var view = new JsonObject();
        view.addProperty("ticker", chosen);
        var rows = new JsonArray();
        var store = StockExchange.store(server).value;
        for (var entry : config.stocks.entrySet()) {
            var state = StockExchange.state(server, entry.getKey());
            var row = new JsonObject();
            row.addProperty("ticker", entry.getKey());
            row.addProperty("name", entry.getValue().name);
            row.addProperty("price", Math.round(state.price * 100));
            row.addProperty("move", state.lastMove);
            row.addProperty("held", StockExchange.held(server, id, entry.getKey()));
            row.addProperty("frozen", state.frozen);
            rows.add(row);
        }
        view.add("stocks", rows);
        if (!chosen.isEmpty()) {
            var stock = config.stocks.get(chosen);
            var state = StockExchange.state(server, chosen);
            view.addProperty("description", stock.description);
            view.addProperty("buy", Stocks.quote(stock, state, true));
            view.addProperty("sell", Stocks.quote(stock, state, false));
            view.addProperty("limit", stock.holdingLimit);
            view.addProperty("interest", Shorts.interest(server, chosen));
            var chart = new JsonArray();
            var prices = store.history.getOrDefault(chosen, List.of());
            for (var sale : prices.subList(Math.max(0, prices.size() - CHART), prices.size())) chart.add(sale.unitPrice());
            view.add("chart", chart);
        }
        view.addProperty("portfolio", StockExchange.value(server, id));
        var margin = new JsonObject();
        margin.addProperty("loan", Margin.loan(server, id));
        margin.addProperty("limit", Margin.limit(server, id));
        margin.addProperty("multiple", Margin.multiple(server, id));
        margin.addProperty("interest", Margin.interest(server, id));
        margin.addProperty("warn", Config.marginWarn());
        margin.addProperty("liquidate", Config.marginLiquidate());
        view.add("margin", margin);
        var shorts = new JsonObject();
        shorts.addProperty("deposit", Config.shortDeposit());
        shorts.addProperty("close", Config.shortClose());
        shorts.addProperty("fee", Config.shortFee());
        var positions = new JsonArray();
        for (var position : Shorts.all(server).getOrDefault(id, Map.of()).values()) {
            var stock = config.stocks.get(position.ticker());
            if (stock == null) continue;
            long cover = Stocks.quote(stock, StockExchange.state(server, position.ticker()), true) * position.shares();
            var row = new JsonObject();
            row.addProperty("ticker", position.ticker());
            row.addProperty("shares", position.shares());
            row.addProperty("entry", position.entry());
            row.addProperty("deposit", position.deposit());
            row.addProperty("pnl", ShortBook.pnl(position.entry(), cover));
            positions.add(row);
        }
        shorts.add("positions", positions);
        view.add("shorts", shorts);
        return view;
    }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        switch (verb) {
            case "select" -> {
                if (args.size() != 1 || !StockExchange.config.stocks.containsKey(args.get(0))) return Result.fail("pluginless.pda.invalid");
                synchronized (SELECTED) { SELECTED.put(player.getUUID(), args.get(0)); }
                return Result.ok("");
            }
            case "buy", "sell" -> {
                var ticker = args.size() == 2 ? ticker(args.get(0)) : null;
                var shares = args.size() == 2 ? shares(args.get(1)) : null;
                return ticker == null || shares == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "stock " + verb + " " + ticker + " " + shares);
            }
            case "open" -> {
                var ticker = args.size() == 2 ? ticker(args.get(0)) : null;
                var shares = args.size() == 2 ? shares(args.get(1)) : null;
                return ticker == null || shares == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "short open " + ticker + " " + shares);
            }
            case "close" -> {
                var ticker = args.size() == 1 ? ticker(args.get(0)) : null;
                return ticker == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "short close " + ticker);
            }
            case "borrow", "repay" -> {
                var amount = args.size() == 1 ? PdaCommands.amount(args.get(0)) : null;
                return amount == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "margin " + verb + " " + amount);
            }
            default -> {
                return Result.fail("pluginless.pda.invalid");
            }
        }
    }

    private static String ticker(String value) {
        return StockExchange.config.stocks.containsKey(value) && PdaCommands.word(value) != null ? value : null;
    }

    private static String shares(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 1 && parsed <= OrderBook.MAX_QUANTITY ? Long.toString(parsed) : null;
        } catch (NumberFormatException e) { return null; }
    }

    static void left(UUID player) {
        synchronized (SELECTED) { SELECTED.remove(player); }
    }
}
