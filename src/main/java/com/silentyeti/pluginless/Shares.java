package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Company shares on a server: ShareBook holds the rules, this adds messages, announcements and saving. */
final class Shares {
    static final Codec<ShareBook> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Market.CODEC.fieldOf("market").forGetter(book -> book.market),
            Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG)).fieldOf("holdings").forGetter(book -> book.holdings),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("lastIssue").forGetter(book -> book.lastIssue)
    ).apply(instance, (market, holdings, lastIssue) -> {
        var book = new ShareBook();
        book.market.orders.putAll(market.orders); book.market.goods.putAll(market.goods); book.market.history.putAll(market.history); book.market.nextId = market.nextId;
        holdings.forEach((asset, held) -> book.holdings.put(asset, new HashMap<>(held)));
        book.lastIssue.putAll(lastIssue);
        return book;
    }));

    static Store<ShareBook> store(MinecraftServer server) {
        return Store.open(server, "shares", CODEC, ShareBook::new);
    }

    private static Accounts accounts(ServerPlayer player, String reason) {
        return Ledger.of(player.level().getServer(), player.getUUID().toString(), reason);
    }

    private static OrderBook.Fee fee(MinecraftServer server) {
        return (seller, proceeds) -> Taxes.percent(proceeds, Config.marketFee(), Income.perk(server, seller, "fee_multiplier"));
    }

    /** Runs a change on a named company's shares and saves both books when it succeeded. */
    private static boolean with(ServerPlayer player, String name, java.util.function.BiPredicate<ShareBook, CompanyBook.Company> change) {
        var server = player.level().getServer();
        var company = Companies.store(server).value.company(name);
        var store = store(server);
        if (company == null || !change.test(store.value, company)) return false;
        store.setDirty();
        Companies.store(server).setDirty();
        return true;
    }

    static boolean issue(ServerPlayer player, String name, long count, long keep, long price) {
        return with(player, name, (book, company) -> book.issue(player.getUUID(), company, count, keep, price, System.currentTimeMillis(),
                accounts(player, "shares." + company.name), fee(player.level().getServer())));
    }

    static boolean order(ServerPlayer player, String name, boolean buy, long price, long quantity) {
        var server = player.level().getServer();
        long limit = Math.round(RankGate.ranks.number(RankGate.rankOf(server, player.getUUID()), "market_orders", Config.marketOrders()));
        return with(player, name, (book, company) -> book.market.count(player.getUUID()) < limit && book.order(player.getUUID(), company, buy, price, quantity,
                System.currentTimeMillis(), accounts(player, "shares." + company.name), fee(server), Config.sharesFounderMin(), Config.sharesMoveLimit()));
    }

    static boolean cancel(ServerPlayer player, long id) {
        var store = store(player.level().getServer());
        if (!store.value.cancel(player.getUUID(), id, accounts(player, "shares.cancel"))) return false;
        store.setDirty();
        return true;
    }

    static boolean takeover(ServerPlayer player, String name) {
        var server = player.level().getServer();
        return with(player, name, (book, company) -> {
            var old = company.owner;
            if (!book.takeover(player.getUUID(), company)) return false;
            Pluginless.LOGGER.info("takeover of {} by {} from {}", company.name, player.getScoreboardName(), Commands.name(server, old));
            server.getPlayerList().broadcastSystemMessage(Component.translatable("pluginless.shares.takeover", player.getScoreboardName(), company.name, Commands.name(server, old)), false);
            return true;
        });
    }

    static boolean dividend(ServerPlayer player, String name, long cents) {
        return with(player, name, (book, company) -> book.dividend(player.getUUID(), company, cents, accounts(player, "company.dividend")) >= 0);
    }

    /** The optional lock-up: money cannot leave a company for a while after it sold shares. */
    static boolean locked(MinecraftServer server, CompanyBook.Company company) {
        long last = store(server).value.lastIssue.getOrDefault(ShareBook.asset(company.id), 0L);
        return Config.sharesLockupMillis() > 0 && System.currentTimeMillis() - last < Config.sharesLockupMillis();
    }

    /** Ends the shares of a company that is closing; the account is split among shareholders first. */
    static void liquidate(MinecraftServer server, CompanyBook.Company company, String reason) {
        var store = store(server);
        store.value.liquidate(company, Ledger.of(server, "server", reason));
        store.setDirty();
    }

    /** A company that missed a wage: shares are liquidated, shops close, and the server is told. */
    static void bankrupt(MinecraftServer server, CompanyBook.Company company) {
        liquidate(server, company, "company.bankrupt");
        var companies = Companies.store(server);
        var returned = companies.value.dissolve(company.owner, company, Ledger.of(server, "server", "company.bankrupt"));
        if (returned != null) returned.forEach((item, count) -> Market.give(server, company.owner, item, count));
        companies.setDirty();
        Pluginless.LOGGER.info("company {} went bankrupt", company.name);
        server.getPlayerList().broadcastSystemMessage(Component.translatable("pluginless.shares.bankrupt", company.name), false);
    }

    /** The public part of a company: shares, price, order book and largest shareholders. */
    static Component page(MinecraftServer server, CompanyBook.Company company) {
        var book = store(server).value;
        String asset = ShareBook.asset(company.id);
        var prices = book.market.history.getOrDefault(asset, List.of()).stream().map(AuctionBook.Sale::unitPrice).toList();
        var holders = book.votes(company.id).entrySet().stream().sorted(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder())).limit(5)
                .map(entry -> Commands.name(server, entry.getKey()) + " " + entry.getValue()).toList();
        var asks = book.market.side(asset, false);
        var bids = book.market.side(asset, true);
        return Component.translatable("pluginless.shares.page", book.outstanding(company.id), prices.isEmpty() ? "-" : Config.format(prices.get(prices.size() - 1)),
                OrderBook.spark(prices, 40), asks.isEmpty() ? "-" : asks.get(0).remaining() + " at " + Config.format(asks.get(0).price()),
                bids.isEmpty() ? "-" : bids.get(0).remaining() + " at " + Config.format(bids.get(0).price()), String.join(", ", holders));
    }

    static Component portfolio(MinecraftServer server, UUID player) {
        var book = store(server).value;
        var result = Component.translatable("pluginless.shares.portfolio");
        for (var company : Companies.store(server).value.companies.values()) {
            long held = book.held(company.id, player);
            if (held > 0) result.append(Component.translatable("pluginless.shares.holding", held, company.name));
        }
        for (var order : book.market.orders.values()) {
            if (order.owner().equals(player)) result.append(Component.translatable("pluginless.market.order", order.id(),
                    Component.translatable(order.buy() ? "pluginless.market.buying" : "pluginless.market.selling"), order.remaining(), order.asset(), Config.format(order.price())));
        }
        long dividends = Ledger.history(server, player).stream().filter(t -> t.to().equals(player.toString()) && t.reason().startsWith("company.dividend")).mapToLong(Ledger.Transaction::amount).sum();
        return result.append(Component.translatable("pluginless.shares.dividends", Config.format(dividends)));
    }
}
