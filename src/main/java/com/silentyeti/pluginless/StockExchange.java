package com.silentyeti.pluginless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Admin stocks on a server: Stocks holds the price model, this trades against the server account and saves. */
final class StockExchange {
    static Stocks config = Stocks.defaults();
    private static final Random RANDOM = new Random();

    /** Everything saved in the world: prices, what players hold, the chart, and today's payouts. */
    static final class Saved {
        final Map<String, Stocks.State> states = new HashMap<>();
        final Map<UUID, Map<String, Long>> holdings = new HashMap<>();
        final Map<String, List<AuctionBook.Sale>> history = new HashMap<>();
        long payoutDay, paid;
    }

    private static final Codec<Stocks.State> STATE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("price").forGetter(state -> state.price),
            Codec.DOUBLE.fieldOf("lastMove").forGetter(state -> state.lastMove),
            Codec.DOUBLE.fieldOf("nudgeLeft").forGetter(state -> state.nudgeLeft),
            Codec.DOUBLE.fieldOf("trend").forGetter(state -> state.trend),
            Codec.LONG.fieldOf("lastUpdate").forGetter(state -> state.lastUpdate),
            Codec.LONG.fieldOf("nudgeUntil").forGetter(state -> state.nudgeUntil),
            Codec.LONG.fieldOf("trendUntil").forGetter(state -> state.trendUntil),
            Codec.BOOL.fieldOf("frozen").forGetter(state -> state.frozen)
    ).apply(instance, (price, lastMove, nudgeLeft, trend, lastUpdate, nudgeUntil, trendUntil, frozen) -> {
        var state = new Stocks.State();
        state.price = price; state.lastMove = lastMove; state.nudgeLeft = nudgeLeft; state.trend = trend;
        state.lastUpdate = lastUpdate; state.nudgeUntil = nudgeUntil; state.trendUntil = trendUntil; state.frozen = frozen;
        return state;
    }));
    private static final Codec<AuctionBook.Sale> SALE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("time").forGetter(AuctionBook.Sale::time),
            Codec.LONG.fieldOf("unitPrice").forGetter(AuctionBook.Sale::unitPrice)
    ).apply(instance, AuctionBook.Sale::new));
    static final Codec<Saved> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, STATE).fieldOf("states").forGetter(saved -> saved.states),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.unboundedMap(Codec.STRING, Codec.LONG)).fieldOf("holdings").forGetter(saved -> saved.holdings),
            Codec.unboundedMap(Codec.STRING, SALE.listOf()).fieldOf("history").forGetter(saved -> saved.history),
            Codec.LONG.fieldOf("payoutDay").forGetter(saved -> saved.payoutDay),
            Codec.LONG.fieldOf("paid").forGetter(saved -> saved.paid)
    ).apply(instance, (states, holdings, history, payoutDay, paid) -> {
        var saved = new Saved();
        saved.states.putAll(states);
        holdings.forEach((id, held) -> saved.holdings.put(id, new HashMap<>(held)));
        history.forEach((ticker, prices) -> saved.history.put(ticker, new ArrayList<>(prices)));
        saved.payoutDay = payoutDay; saved.paid = paid;
        return saved;
    }));

    static String load() {
        var file = FMLPaths.CONFIGDIR.get().resolve(Pluginless.MOD_ID).resolve("stocks.json");
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, gson.toJson(Stocks.defaults()));
            }
            var loaded = gson.fromJson(Files.readString(file), Stocks.class);
            var problem = loaded == null ? "the file is empty" : loaded.problem();
            if (problem == null) config = loaded;
            return problem;
        } catch (IOException | JsonParseException e) {
            return e.getMessage();
        }
    }

    static Store<Saved> store(MinecraftServer server) {
        return Store.open(server, "stocks", CODEC, Saved::new);
    }

    /** A stock's state, started at its configured price the first time it is seen; null for an unknown ticker. */
    static Stocks.State state(MinecraftServer server, String ticker) {
        var stock = config.stocks.get(ticker);
        if (stock == null) return null;
        return store(server).value.states.computeIfAbsent(ticker, key -> {
            var state = new Stocks.State();
            state.price = stock.start;
            state.lastUpdate = System.currentTimeMillis();
            return state;
        });
    }

    static long held(MinecraftServer server, UUID player, String ticker) {
        return store(server).value.holdings.getOrDefault(player, Map.of()).getOrDefault(ticker, 0L);
    }

    /** What the player's holdings would fetch at today's sell quotes, without moving any price. */
    static long value(MinecraftServer server, UUID player) {
        long total = 0;
        for (var holding : store(server).value.holdings.getOrDefault(player, Map.of()).entrySet()) {
            var stock = config.stocks.get(holding.getKey());
            if (stock != null) total += Stocks.quote(stock, state(server, holding.getKey()), false) * holding.getValue();
        }
        return total;
    }

    /** Sells every unhalted holding at once for a forced sale; each lot moves its price like a normal sale. Returns the proceeds, paid to nobody. */
    static long sellAll(MinecraftServer server, UUID player) {
        var store = store(server);
        var holdings = store.value.holdings.get(player);
        if (holdings == null) return 0;
        long total = 0;
        for (var holding : List.copyOf(holdings.entrySet())) {
            var stock = config.stocks.get(holding.getKey());
            var state = stock == null ? null : state(server, holding.getKey());
            if (state == null || state.frozen) continue;
            Stocks.pressure(stock, state, holding.getValue(), false);
            total += Stocks.quote(stock, state, false) * holding.getValue();
            holdings.remove(holding.getKey());
            record(store, holding.getKey(), state, System.currentTimeMillis());
        }
        if (holdings.isEmpty()) store.value.holdings.remove(player);
        store.setDirty();
        return total;
    }

    private static void record(Store<Saved> store, String ticker, Stocks.State state, long now) {
        var prices = store.value.history.computeIfAbsent(ticker, key -> new ArrayList<>());
        prices.add(new AuctionBook.Sale(now, Math.round(state.price * 100)));
        // shortcut: the last 200 prices per stock, move to time buckets when charts need longer history
        if (prices.size() > 200) prices.remove(0);
        store.setDirty();
    }

    static void tick(MinecraftServer server, long now) {
        var store = store(server);
        for (var entry : config.stocks.entrySet()) {
            var stock = entry.getValue();
            var state = state(server, entry.getKey());
            if (state.frozen || now - state.lastUpdate < stock.updateSeconds * 1000L) continue;
            double before = state.price;
            double shock = Stocks.step(stock, state, now, RANDOM);
            record(store, entry.getKey(), state, now);
            if (shock != 0) headline(server, stock, state, (state.price / before - 1) * 100);
        }
    }

    private static void headline(MinecraftServer server, Stocks.Stock stock, Stocks.State state, double move) {
        if (stock.headline.isEmpty()) return;
        server.getPlayerList().broadcastSystemMessage(Text.format(stock.headline.replace("{name}", stock.name)
                .replace("{price}", Config.format(Math.round(state.price * 100))).replace("{move}", "%+.1f".formatted(move))), false);
    }

    static boolean trade(ServerPlayer player, String ticker, long shares, boolean buy) {
        var server = player.level().getServer();
        var stock = config.stocks.get(ticker);
        var state = state(server, ticker);
        long held = held(server, player.getUUID(), ticker);
        if (stock == null || state.frozen || shares <= 0 || shares > OrderBook.MAX_QUANTITY || (!buy && !Margin.allowsSale(server, player.getUUID(), ticker, shares)) || (buy ? held + shares > stock.holdingLimit : held < shares)) return false;
        var store = store(server);
        double before = state.price;
        Stocks.pressure(stock, state, shares, buy);
        long cents = Stocks.quote(stock, state, buy) * shares;
        boolean done;
        if (buy) done = Ledger.collect(server, player.getUUID(), cents, "stock." + ticker);
        else {
            long today = System.currentTimeMillis() / 86_400_000;
            if (store.value.payoutDay != today) { store.value.payoutDay = today; store.value.paid = 0; }
            long cap = Math.round(config.dailyPayoutCap * 100);
            done = (cap == 0 || store.value.paid + cents <= cap) && Ledger.payout(server, player.getUUID(), cents, "stock." + ticker);
            if (done) store.value.paid += cents;
        }
        // A refused trade must not have moved the price.
        if (!done) { state.price = before; return false; }
        var holdings = store.value.holdings.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
        if (holdings.merge(ticker, buy ? shares : -shares, Long::sum) == 0) holdings.remove(ticker);
        if (holdings.isEmpty()) store.value.holdings.remove(player.getUUID());
        record(store, ticker, state, System.currentTimeMillis());
        return true;
    }

    /** Admin controls; every use is written to the server log and nothing is announced to players. */
    static boolean admin(MinecraftServer server, String who, String ticker, String action, double value, long duration) {
        var stock = config.stocks.get(ticker);
        var state = state(server, ticker);
        if (stock == null || !Double.isFinite(value)) return false;
        long now = System.currentTimeMillis();
        switch (action) {
            case "nudge" -> { state.nudgeLeft = value; state.nudgeUntil = now + duration; }
            case "trend" -> { state.trend = value; state.trendUntil = now + duration; }
            case "set" -> { if (value < stock.floor || value > stock.ceiling) return false; state.price = value; }
            case "shock" -> {
                double before = state.price;
                state.price = Stocks.clamp(stock, state.price * (1 + value / 100));
                headline(server, stock, state, (state.price / before - 1) * 100);
            }
            case "freeze" -> state.frozen = true;
            case "unfreeze" -> { state.frozen = false; state.lastUpdate = now; }
            default -> { return false; }
        }
        record(store(server), ticker, state, now);
        Pluginless.LOGGER.info("stock {} {} {} over {}ms by {}", action, ticker, value, duration, who);
        return true;
    }

    static Component list(MinecraftServer server) {
        var result = Component.translatable("pluginless.stock.list");
        for (var ticker : config.stocks.keySet()) result.append(line(server, ticker));
        return result;
    }

    private static Component line(MinecraftServer server, String ticker) {
        var stock = config.stocks.get(ticker);
        var state = state(server, ticker);
        var prices = store(server).value.history.getOrDefault(ticker, List.of()).stream().map(AuctionBook.Sale::unitPrice).toList();
        return Component.translatable("pluginless.stock.line", ticker, stock.name, Config.format(Math.round(state.price * 100)), OrderBook.spark(prices, 40),
                Component.translatable(state.frozen ? "pluginless.stock.frozen" : "pluginless.stock.open"));
    }

    static Component info(MinecraftServer server, UUID player, String ticker) {
        var stock = config.stocks.get(ticker);
        if (stock == null) return Component.translatable("pluginless.stock.unknown");
        var state = state(server, ticker);
        return Component.translatable("pluginless.stock.info", stock.description, Config.format(Stocks.quote(stock, state, true)),
                Config.format(Stocks.quote(stock, state, false)), held(server, player, ticker), stock.holdingLimit).append(line(server, ticker));
    }

    static Component portfolio(MinecraftServer server, UUID player) {
        var result = Component.translatable("pluginless.stock.portfolio");
        long total = 0;
        for (var holding : store(server).value.holdings.getOrDefault(player, Map.of()).entrySet()) {
            var stock = config.stocks.get(holding.getKey());
            if (stock == null) continue;
            long worth = Stocks.quote(stock, state(server, holding.getKey()), false) * holding.getValue();
            total += worth;
            result.append(Component.translatable("pluginless.stock.holding", holding.getValue(), holding.getKey(), Config.format(worth)));
        }
        return result.append(Component.translatable("pluginless.stock.total", Config.format(total)));
    }
}
