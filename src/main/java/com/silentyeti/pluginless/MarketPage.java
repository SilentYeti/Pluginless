package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** The item market: pick an item, see the price chart and the best orders, trade now or leave an order. */
final class MarketPage implements PdaPage {
    static final int LEVELS = 5;
    static final int CHART = 40;
    private static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private record Query(String asset, long quantity) {}

    private static final Map<UUID, Query> QUERIES = new HashMap<>();

    public String id() { return "market"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var id = player.getUUID();
        var book = Market.store(server).value;
        Query query;
        synchronized (QUERIES) { query = QUERIES.getOrDefault(id, new Query("", 1)); }
        var items = Features.settings.marketItems;
        var asset = items.contains(query.asset()) ? query.asset() : items.isEmpty() ? "" : items.get(0);
        var view = new JsonObject();
        view.addProperty("asset", asset);
        view.addProperty("quantity", query.quantity());
        var list = new JsonArray();
        for (var item : items) {
            var row = new JsonObject();
            row.addProperty("id", item);
            var prices = book.history.getOrDefault(item, List.of());
            row.addProperty("last", prices.isEmpty() ? 0 : prices.get(prices.size() - 1).unitPrice());
            var asks = book.side(item, false);
            var bids = book.side(item, true);
            row.addProperty("ask", asks.isEmpty() ? 0 : asks.get(0).price());
            row.addProperty("bid", bids.isEmpty() ? 0 : bids.get(0).price());
            list.add(row);
        }
        view.add("items", list);
        if (!asset.isEmpty()) {
            view.add("asks", levels(book.side(asset, false)));
            view.add("bids", levels(book.side(asset, true)));
            var chart = new JsonArray();
            var prices = book.history.getOrDefault(asset, List.of());
            for (var sale : prices.subList(Math.max(0, prices.size() - CHART), prices.size())) chart.add(sale.unitPrice());
            view.add("chart", chart);
            view.addProperty("held", Market.held(player, asset));
            view.addProperty("quoteBuy", book.quote(id, asset, true, query.quantity()));
            view.addProperty("quoteSell", book.quote(id, asset, false, query.quantity()));
        }
        var mine = new JsonArray();
        for (var order : book.orders.values()) {
            if (!order.owner().equals(id)) continue;
            var row = new JsonObject();
            row.addProperty("id", order.id());
            row.addProperty("asset", order.asset());
            row.addProperty("buy", order.buy());
            row.addProperty("remaining", order.remaining());
            row.addProperty("price", order.price());
            mine.add(row);
        }
        view.add("orders", mine);
        long waiting = book.goods.getOrDefault(id, Map.of()).values().stream().mapToLong(Long::longValue).sum();
        view.addProperty("goods", waiting);
        view.addProperty("fee", Config.marketFee());
        view.addProperty("limit", Math.round(RankGate.ranks.number(RankGate.rankOf(server, id), "market_orders", Config.marketOrders())));
        return view;
    }

    private static JsonArray levels(List<OrderBook.Order> side) {
        var array = new JsonArray();
        for (var order : side.subList(0, Math.min(LEVELS, side.size()))) {
            var row = new JsonObject();
            row.addProperty("quantity", order.remaining());
            row.addProperty("price", order.price());
            array.add(row);
        }
        return array;
    }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        switch (verb) {
            case "query" -> {
                if (args.size() != 2 || !Features.settings.marketItems.contains(args.get(0))) return Result.fail("pluginless.pda.invalid");
                long quantity;
                try { quantity = Long.parseLong(args.get(1)); } catch (NumberFormatException e) { return Result.fail("pluginless.pda.invalid"); }
                if (quantity < 1 || quantity > OrderBook.MAX_QUANTITY) return Result.fail("pluginless.pda.invalid");
                synchronized (QUERIES) { QUERIES.put(player.getUUID(), new Query(args.get(0), quantity)); }
                return Result.ok("");
            }
            case "buy", "sell" -> {
                var item = args.size() == 3 ? item(args.get(0)) : null;
                var quantity = args.size() == 3 ? quantity(args.get(1)) : null;
                var price = args.size() == 3 ? PdaCommands.amount(args.get(2)) : null;
                return item == null || quantity == null || price == null ? Result.fail("pluginless.pda.invalid")
                        : PdaCommands.run(player, "market " + verb + " " + item + " " + quantity + " " + price);
            }
            case "instabuy", "instasell" -> {
                var item = args.size() == 2 ? item(args.get(0)) : null;
                var quantity = args.size() == 2 ? quantity(args.get(1)) : null;
                return item == null || quantity == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "market " + verb + " " + item + " " + quantity);
            }
            case "cancel" -> {
                var id = args.size() == 1 ? quantity(args.get(0)) : null;
                return id == null ? Result.fail("pluginless.pda.invalid") : PdaCommands.run(player, "market cancel " + id);
            }
            case "collect" -> {
                return PdaCommands.run(player, "market collect");
            }
            default -> {
                return Result.fail("pluginless.pda.invalid");
            }
        }
    }

    private static String item(String value) {
        return ITEM.matcher(value).matches() && Features.settings.marketItems.contains(value) ? value : null;
    }

    private static String quantity(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 1 && parsed <= OrderBook.MAX_QUANTITY ? Long.toString(parsed) : null;
        } catch (NumberFormatException e) { return null; }
    }

    static void left(UUID player) {
        synchronized (QUERIES) { QUERIES.remove(player); }
    }
}
