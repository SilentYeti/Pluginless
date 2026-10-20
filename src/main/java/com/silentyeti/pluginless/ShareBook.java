package com.silentyeti.pluginless;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Company shares, without Minecraft. Shares trade on an OrderBook; a share is a vote, so whoever holds more
 * than half of the shares in players' hands can take the company. Nothing here protects an investor from the
 * owner, or the owner from a buyer: the protections are optional limits the caller passes in.
 */
public final class ShareBook {
    public final OrderBook market = new OrderBook();
    /** Asset ("share:<company id>") to holder to shares. Shares waiting in a sell order are in the order, not here. */
    public final Map<String, Map<UUID, Long>> holdings = new HashMap<>();
    /** When each company last issued shares, for the optional lock-up. */
    public final Map<String, Long> lastIssue = new HashMap<>();

    public static String asset(long company) {
        return "share:" + company;
    }

    public long held(long company, UUID player) {
        return holdings.getOrDefault(asset(company), Map.of()).getOrDefault(player, 0L);
    }

    /** Shares each player controls: held, plus their own waiting to be sold. The company's unsold shares do not vote. */
    public Map<UUID, Long> votes(long company) {
        var votes = new LinkedHashMap<>(holdings.getOrDefault(asset(company), Map.of()));
        for (var order : market.orders.values()) {
            if (!order.buy() && order.asset().equals(asset(company)) && !Accounts.system(order.owner())) votes.merge(order.owner(), order.remaining(), Long::sum);
        }
        return votes;
    }

    public long outstanding(long company) {
        return votes(company).values().stream().mapToLong(Long::longValue).sum();
    }

    /** Bought shares and cancelled sell orders come back through the order book's goods; move them into holdings. */
    private void settle() {
        for (var entry : List.copyOf(market.goods.entrySet())) {
            for (var good : List.copyOf(entry.getValue().entrySet())) {
                long shares = market.take(entry.getKey(), good.getKey(), Long.MAX_VALUE);
                // Unsold shares returned to the company itself simply cease to exist.
                if (!Accounts.system(entry.getKey())) holdings.computeIfAbsent(good.getKey(), key -> new HashMap<>()).merge(entry.getKey(), shares, Long::sum);
            }
        }
    }

    /** The owner creates shares: some kept, the rest offered for sale by the company, whose account gets the proceeds. */
    public boolean issue(UUID by, CompanyBook.Company company, long count, long keep, long price, long now, Accounts accounts, OrderBook.Fee fee) {
        if (!by.equals(company.owner) || count <= 0 || keep < 0 || keep > count || count > OrderBook.MAX_QUANTITY) return false;
        if (count > keep && !market.place(CompanyBook.account(company.id), asset(company.id), false, price, count - keep, now, accounts, fee)) return false;
        if (keep > 0) holdings.computeIfAbsent(asset(company.id), key -> new HashMap<>()).merge(by, keep, Long::sum);
        lastIssue.put(asset(company.id), now);
        settle();
        return true;
    }

    /**
     * A player's buy or sell order. founderMinPercent stops the owner selling below that share of the votes, and
     * moveLimitPercent keeps the price within that distance of the last trade; 0 switches either off.
     */
    public boolean order(UUID player, CompanyBook.Company company, boolean buy, long price, long quantity, long now, Accounts accounts, OrderBook.Fee fee,
                         double founderMinPercent, double moveLimitPercent) {
        String asset = asset(company.id);
        var sales = market.history.getOrDefault(asset, List.of());
        if (moveLimitPercent > 0 && !sales.isEmpty()) {
            long last = sales.get(sales.size() - 1).unitPrice();
            if (Math.abs(price - last) > last * moveLimitPercent / 100) return false;
        }
        if (!buy) {
            if (quantity <= 0 || held(company.id, player) < quantity || price <= 0 || price > OrderBook.MAX_PRICE) return false;
            // Shares in a sell order still vote, so the founder's floor is checked against what is truly given up.
            if (founderMinPercent > 0 && player.equals(company.owner)
                    && (held(company.id, player) - quantity) * 100.0 < founderMinPercent * outstanding(company.id)) return false;
            var held = holdings.get(asset);
            if (held.merge(player, -quantity, Long::sum) == 0) held.remove(player);
        }
        boolean placed = market.place(player, asset, buy, price, quantity, now, accounts, fee);
        if (!placed && !buy) holdings.get(asset).merge(player, quantity, Long::sum);
        settle();
        return placed;
    }

    public boolean cancel(UUID player, long id, Accounts accounts) {
        boolean cancelled = market.cancel(player, id, accounts);
        settle();
        return cancelled;
    }

    /** More than half of the votes makes the holder the owner; the old owner keeps only their shares. */
    public boolean takeover(UUID player, CompanyBook.Company company) {
        if (player.equals(company.owner) || votes(company.id).getOrDefault(player, 0L) * 2 <= outstanding(company.id)) return false;
        company.owner = player;
        company.members.remove(player);
        company.wages.remove(player);
        return true;
    }

    /** Splits an amount among shareholders by votes, rounding down; what cannot be divided stays put. Returns what was paid. */
    private long split(long company, long total, Accounts accounts) {
        long outstanding = outstanding(company), paid = 0;
        if (outstanding == 0) return 0;
        for (var vote : votes(company).entrySet()) {
            long part = (long) ((double) total * vote.getValue() / outstanding);
            if (part > 0 && accounts.transfer(CompanyBook.account(company), vote.getKey(), part)) paid += part;
        }
        return paid;
    }

    /** Paid whenever the owner or a manager likes, from whatever is in the account; -1 when refused. */
    public long dividend(UUID by, CompanyBook.Company company, long total, Accounts accounts) {
        if (!company.can(by, "spend") || total <= 0 || accounts.balance(CompanyBook.account(company.id)) < total) return -1;
        return split(company.id, total, accounts);
    }

    /** Ends a company's shares: open orders are cancelled, the account is split among shareholders, the shares vanish. */
    public void liquidate(CompanyBook.Company company, Accounts accounts) {
        String asset = asset(company.id);
        // Votes are read before the sell orders are cancelled, and cancelling returns those shares to the same holders.
        for (var order : List.copyOf(market.orders.values())) {
            if (order.asset().equals(asset)) market.cancel(order.owner(), order.id(), accounts);
        }
        settle();
        split(company.id, accounts.balance(CompanyBook.account(company.id)), accounts);
        holdings.remove(asset);
        lastIssue.remove(asset);
        market.history.remove(asset);
    }
}
