package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bets against admin stocks: the server takes the other side. The security sits in escrow; a shortfall after a forced close becomes bank debt. */
public final class Shorts {
    private static final Codec<ShortBook.Position> POSITION = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("ticker").forGetter(ShortBook.Position::ticker),
            Codec.LONG.fieldOf("shares").forGetter(ShortBook.Position::shares),
            Codec.LONG.fieldOf("entry").forGetter(ShortBook.Position::entry),
            Codec.LONG.fieldOf("deposit").forGetter(ShortBook.Position::deposit),
            Codec.LONG.fieldOf("updated").forGetter(ShortBook.Position::updated)
    ).apply(instance, ShortBook.Position::new));

    static final Codec<Map<UUID, Map<String, ShortBook.Position>>> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC,
            Codec.unboundedMap(Codec.STRING, POSITION).<Map<String, ShortBook.Position>>xmap(HashMap::new, map -> map))
            .<Map<UUID, Map<String, ShortBook.Position>>>xmap(HashMap::new, map -> map);

    private static Store<Map<UUID, Map<String, ShortBook.Position>>> positions(MinecraftServer server) {
        return Store.open(server, "shorts", CODEC, HashMap::new);
    }

    static Map<UUID, Map<String, ShortBook.Position>> all(MinecraftServer server) { return positions(server).value; }

    static ShortBook.Position position(MinecraftServer server, UUID player, String ticker) {
        return positions(server).value.getOrDefault(player, Map.of()).get(ticker);
    }

    /** Shares currently sold short in a stock, by everyone. */
    static long interest(MinecraftServer server, String ticker) {
        return positions(server).value.values().stream().map(map -> map.get(ticker)).filter(java.util.Objects::nonNull).mapToLong(ShortBook.Position::shares).sum();
    }

    static boolean open(ServerPlayer player, String ticker, long shares) {
        var server = player.level().getServer();
        var stock = StockExchange.config.stocks.get(ticker);
        var state = StockExchange.state(server, ticker);
        var id = player.getUUID();
        if (stock == null || state.frozen || shares <= 0 || shares > stock.holdingLimit || position(server, id, ticker) != null || Bank.debt(server, id) != 0) return false;
        double before = state.price;
        // Selling short pushes the price down like any sale.
        Stocks.pressure(stock, state, shares, false);
        long entry = Stocks.quote(stock, state, false) * shares, deposit = ShortBook.requiredDeposit(entry, Config.shortDeposit());
        if (entry <= 0 || !Ledger.of(server, id.toString(), "short.deposit").release(id, Accounts.ESCROW, deposit)) { state.price = before; return false; }
        var store = positions(server);
        store.value.computeIfAbsent(id, key -> new HashMap<>()).put(ticker, new ShortBook.Position(ticker, shares, entry, deposit, System.currentTimeMillis()));
        store.setDirty();
        StockExchange.record(StockExchange.store(server), ticker, state, System.currentTimeMillis());
        return true;
    }

    static boolean close(ServerPlayer player, String ticker) {
        var server = player.level().getServer();
        var position = position(server, player.getUUID(), ticker);
        return position != null && cover(server, player.getUUID(), position, false);
    }

    /** One step: buy back at the buy quote, settle the money, drop the position. A payout that fails changes nothing, so it can be retried. */
    private static boolean cover(MinecraftServer server, UUID player, ShortBook.Position position, boolean forced) {
        var stock = StockExchange.config.stocks.get(position.ticker());
        var state = stock == null ? null : StockExchange.state(server, position.ticker());
        if (state == null || state.frozen) return false;
        double before = state.price;
        Stocks.pressure(stock, state, position.shares(), true);
        var result = ShortBook.settle(position.deposit(), ShortBook.pnl(position.entry(), Stocks.quote(stock, state, true) * position.shares()));
        if (result.profit() > 0 && !Ledger.payout(server, player, result.profit(), "short.profit")) { state.price = before; return false; }
        var accounts = Ledger.of(server, player.toString(), "short.close");
        if (result.toServer() > 0) accounts.release(Accounts.ESCROW, Accounts.SERVER, result.toServer());
        if (result.toPlayer() > 0) accounts.release(Accounts.ESCROW, player, result.toPlayer());
        if (result.shortfall() > 0 && !Bank.addDebt(server, player, result.shortfall(), "short"))
            Pluginless.LOGGER.warn("short: could not record {} shortfall for {}", result.shortfall(), player);
        var store = positions(server);
        var own = store.value.get(player);
        own.remove(position.ticker());
        if (own.isEmpty()) store.value.remove(player);
        store.setDirty();
        StockExchange.record(StockExchange.store(server), position.ticker(), state, System.currentTimeMillis());
        Pluginless.LOGGER.info("short: closed {} {} x{} profit {} lost {} shortfall {}", player, position.ticker(), position.shares(), result.profit(), result.toServer(), result.shortfall());
        var online = server.getPlayerList().getPlayer(player);
        if (online != null && forced) online.sendSystemMessage(Component.translatable("pluginless.short.closed", position.ticker(), Config.format(result.toPlayer() + result.profit()), Config.format(result.shortfall())));
        return true;
    }

    static void tick(MinecraftServer server, long now) {
        var store = positions(server);
        for (var player : List.copyOf(store.value.keySet())) {
            for (var position : List.copyOf(store.value.get(player).values())) {
                var stock = StockExchange.config.stocks.get(position.ticker());
                if (stock == null) continue;
                position = charge(server, player, position, now);
                var state = StockExchange.state(server, position.ticker());
                long cost = Stocks.quote(stock, state, true) * position.shares();
                if (!state.frozen && ShortBook.shouldClose(position.deposit(), ShortBook.pnl(position.entry(), cost), Config.shortClose())) cover(server, player, position, true);
            }
        }
    }

    /** Takes the fee out of the deposit; the clock only moves when a whole cent was due, so small fees add up instead of rounding away. */
    private static ShortBook.Position charge(MinecraftServer server, UUID player, ShortBook.Position position, long now) {
        long fee = Math.min(position.deposit(), ShortBook.fee(position.entry(), Config.shortFee(), now - position.updated()));
        if (fee <= 0) return position;
        if (!Ledger.of(server, player.toString(), "short.fee").release(Accounts.ESCROW, Accounts.SERVER, fee)) return position;
        var charged = new ShortBook.Position(position.ticker(), position.shares(), position.entry(), position.deposit() - fee, now);
        positions(server).value.get(player).put(position.ticker(), charged);
        positions(server).setDirty();
        return charged;
    }

    static Component summary(MinecraftServer server, UUID player) {
        var result = Component.translatable("pluginless.short.summary", Math.round(Config.shortDeposit() * 100), Math.round(Config.shortClose() * 100), Config.shortFee());
        for (var position : positions(server).value.getOrDefault(player, Map.of()).values()) {
            var stock = StockExchange.config.stocks.get(position.ticker());
            if (stock == null) continue;
            long pnl = ShortBook.pnl(position.entry(), Stocks.quote(stock, StockExchange.state(server, position.ticker()), true) * position.shares());
            result.append(Component.translatable("pluginless.short.line", position.shares(), position.ticker(), Config.format(position.entry()), Config.format(position.deposit()),
                    (pnl < 0 ? "-" : "+") + Config.format(Math.abs(pnl))));
        }
        return result;
    }

    private Shorts() {}
}
