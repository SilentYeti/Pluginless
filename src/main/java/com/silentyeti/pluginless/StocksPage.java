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
            // Admin text has no length rule; the packet is bounded, and a clip is marked with "..." so it never looks complete.
            view.addProperty("description", stock.description.length() > 1000 ? stock.description.substring(0, 997) + "..." : stock.description);
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
        view.add("shares", shares(server, id));
        return view;
    }

    /** Company shares: the companies that have shares out (or that you hold), their order book tops, and your own orders. */
    private static JsonObject shares(MinecraftServer server, UUID id) {
        var result = new JsonObject();
        var book = Shares.store(server).value;
        var companies = new JsonArray();
        var names = new HashMap<String, String>();
        for (var company : Companies.store(server).value.companies.values()) {
            long outstanding = book.outstanding(company.id), held = book.held(company.id, id);
            names.put(ShareBook.asset(company.id), company.name);
            if (outstanding == 0 && held == 0) continue;
            var asset = ShareBook.asset(company.id);
            var asks = book.market.side(asset, false);
            var bids = book.market.side(asset, true);
            var row = new JsonObject();
            row.addProperty("name", company.name);
            row.addProperty("held", held);
            row.addProperty("votes", book.votes(company.id).getOrDefault(id, 0L));
            row.addProperty("outstanding", outstanding);
            row.addProperty("ask", asks.isEmpty() ? 0 : asks.get(0).price());
            row.addProperty("askQuantity", asks.isEmpty() ? 0 : asks.get(0).remaining());
            row.addProperty("bid", bids.isEmpty() ? 0 : bids.get(0).price());
            row.addProperty("bidQuantity", bids.isEmpty() ? 0 : bids.get(0).remaining());
            row.addProperty("owner", id.equals(company.owner));
            companies.add(row);
        }
        result.add("companies", companies);
        var orders = new JsonArray();
        for (var order : book.market.orders.values()) {
            if (!order.owner().equals(id) || !names.containsKey(order.asset())) continue;
            var row = new JsonObject();
            row.addProperty("id", order.id());
            row.addProperty("company", names.get(order.asset()));
            row.addProperty("buy", order.buy());
            row.addProperty("quantity", order.remaining());
            row.addProperty("price", order.price());
            orders.add(row);
        }
        result.add("orders", orders);
        return result;
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
            case "sbuy", "ssell" -> {
                var company = args.size() == 3 ? company(player, args.get(0)) : null;
                var shares = args.size() == 3 ? shares(args.get(1)) : null;
                var price = args.size() == 3 ? PdaCommands.amount(args.get(2)) : null;
                return company == null || shares == null || price == null ? Result.fail("pluginless.pda.invalid")
                        : PdaCommands.run(player, "shares " + verb.substring(1) + " " + company + " " + shares + " " + price);
            }
            case "scancel" -> {
                var order = args.size() == 1 ? shares(args.get(0)) : null;
                return order == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "shares cancel " + order);
            }
            case "takeover" -> {
                var company = args.size() == 1 ? company(player, args.get(0)) : null;
                return company == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "shares takeover " + company);
            }
            case "dividend" -> {
                var company = args.size() == 2 ? company(player, args.get(0)) : null;
                var amount = args.size() == 2 ? PdaCommands.amount(args.get(1)) : null;
                return company == null || amount == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "shares dividend " + company + " " + amount);
            }
            default -> {
                return Result.fail("pluginless.pda.invalid");
            }
        }
    }

    /** The name of an existing company as the command takes it (one word); null for anything else. */
    private static String company(ServerPlayer player, String value) {
        var server = player.level().getServer();
        if (server == null || PdaCommands.word(value) == null) return null;
        var company = Companies.store(server).value.company(value);
        return company == null ? null : company.name;
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
