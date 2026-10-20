package com.silentyeti.pluginless;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Buy and sell orders for interchangeable assets (bulk items now, shares later), without Minecraft.
 * A buy order's money waits in the escrow account and a sell order's goods wait in the order, so a
 * matched order can always be paid out. Trades happen at the price of the order that was waiting.
 */
public final class OrderBook {
    public record Order(long id, UUID owner, String asset, boolean buy, long price, long remaining) {}
    public interface Fee { long of(UUID seller, long proceeds); }
    public static final long MAX_QUANTITY = 1_000_000, MAX_PRICE = 1_000_000_000_000L;

    public final Map<Long, Order> orders = new LinkedHashMap<>();
    /** Goods a player can collect: bought, or returned by a cancelled sell order. */
    public final Map<UUID, Map<String, Long>> goods = new HashMap<>();
    public final Map<String, List<AuctionBook.Sale>> history = new HashMap<>();
    public long nextId = 1;

    public long count(UUID owner) {
        return orders.values().stream().filter(order -> order.owner().equals(owner)).count();
    }

    /** Waiting orders on one side, best price first, then oldest. */
    public List<Order> side(String asset, boolean buy) {
        Comparator<Order> price = Comparator.comparingLong(Order::price);
        return orders.values().stream().filter(order -> order.asset().equals(asset) && order.buy() == buy)
                .sorted((buy ? price.reversed() : price).thenComparingLong(Order::id)).toList();
    }

    /** The limit price that fills the whole quantity at once against other players' orders; -1 when there is not enough. */
    public long quote(UUID owner, String asset, boolean buy, long quantity) {
        long left = quantity;
        for (var order : side(asset, !buy)) {
            if (order.owner().equals(owner)) continue;
            left -= order.remaining();
            if (left <= 0) return order.price();
        }
        return -1;
    }

    /**
     * Places an order and fills what it can. A buyer's money is taken here; a seller's goods must already
     * have been taken by the caller. False when the order is invalid or the buyer cannot pay.
     */
    public boolean place(UUID owner, String asset, boolean buy, long price, long quantity, long now, Accounts accounts, Fee fee) {
        if (price <= 0 || price > MAX_PRICE || quantity <= 0 || quantity > MAX_QUANTITY) return false;
        if (buy && !accounts.release(owner, Accounts.ESCROW, price * quantity)) return false;
        long left = quantity;
        for (var resting : side(asset, !buy)) {
            if (left == 0 || (buy ? resting.price() > price : resting.price() < price)) break;
            if (resting.owner().equals(owner)) continue;
            long filled = Math.min(left, resting.remaining());
            UUID seller = buy ? resting.owner() : owner, buyer = buy ? owner : resting.owner();
            long proceeds = filled * resting.price();
            long cut = Math.max(0, Math.min(proceeds, fee.of(seller, proceeds)));
            if (cut > 0) accounts.release(Accounts.ESCROW, Accounts.SERVER, cut);
            if (proceeds > cut) accounts.transfer(Accounts.ESCROW, seller, proceeds - cut);
            // The buyer escrowed their own limit; a cheaper fill returns the difference.
            if (buy && price > resting.price()) accounts.release(Accounts.ESCROW, owner, (price - resting.price()) * filled);
            goods.computeIfAbsent(buyer, key -> new HashMap<>()).merge(asset, filled, Long::sum);
            if (resting.remaining() == filled) orders.remove(resting.id());
            else orders.put(resting.id(), new Order(resting.id(), resting.owner(), asset, resting.buy(), resting.price(), resting.remaining() - filled));
            var sales = history.computeIfAbsent(asset, key -> new ArrayList<>());
            sales.add(new AuctionBook.Sale(now, resting.price()));
            // shortcut: the last 200 trades per asset, move to time buckets when charts need longer history
            if (sales.size() > 200) sales.remove(0);
            left -= filled;
        }
        if (left > 0) orders.put(nextId, new Order(nextId++, owner, asset, buy, price, left));
        return true;
    }

    /** Returns the unfilled part: money to the buyer, goods to the seller's collection. */
    public boolean cancel(UUID owner, long id, Accounts accounts) {
        var order = orders.get(id);
        if (order == null || !order.owner().equals(owner)) return false;
        orders.remove(id);
        if (order.buy()) accounts.release(Accounts.ESCROW, owner, order.price() * order.remaining());
        else goods.computeIfAbsent(owner, key -> new HashMap<>()).merge(order.asset(), order.remaining(), Long::sum);
        return true;
    }

    /** Takes up to the wanted quantity of collected goods; the caller hands exactly that many over. */
    public long take(UUID owner, String asset, long wanted) {
        var held = goods.get(owner);
        long taken = held == null ? 0 : Math.min(wanted, held.getOrDefault(asset, 0L));
        if (taken <= 0) return 0;
        if (held.merge(asset, -taken, Long::sum) == 0) held.remove(asset);
        if (held.isEmpty()) goods.remove(owner);
        return taken;
    }

    /** A one-line chart of prices, oldest to newest, scaled between the lowest and highest. */
    public static String spark(List<Long> prices, int width) {
        var shown = prices.subList(Math.max(0, prices.size() - width), prices.size());
        long low = shown.stream().mapToLong(Long::longValue).min().orElse(0), high = shown.stream().mapToLong(Long::longValue).max().orElse(0);
        var chart = new StringBuilder();
        for (long price : shown) chart.append("▁▂▃▄▅▆▇█".charAt(high == low ? 3 : (int) ((price - low) * 7 / (high - low))));
        return chart.toString();
    }
}
