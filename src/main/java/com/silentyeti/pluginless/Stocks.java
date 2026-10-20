package com.silentyeti.pluginless;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Admin-defined stocks: the stocks.json model and the price model, without Minecraft. Percentages are of the price. */
public final class Stocks {
    public static final class Stock {
        public String name = "", description = "";
        public double start = 100, floor = 1, ceiling = 10_000;
        /** Largest random move per update, and how often the price updates. */
        public double volatility = 1;
        public int updateSeconds = 60;
        /** A steady pull, percent per day. */
        public double drift = 0;
        /** Return to a level: the share of the gap to target closed per day; target 0 switches it off. */
        public double target = 0, pull = 0;
        /** A rise and fall of this size over each period. */
        public double cycleAmplitude = 0, cycleHours = 24;
        /** The share of the previous move that carries into the next. */
        public double momentum = 0;
        /** Chance per day of a sudden move of this size, up or down. */
        public double shockChance = 0, shockSize = 0;
        /** Percent the price moves per 100 shares players buy or sell. */
        public double pressure = 0;
        /** Gap between the buying and selling price. */
        public double spread = 1;
        public long holdingLimit = 1000;
        /** Broadcast on a shock: {name}, {price} and {move} are filled in. Empty for silence. */
        public String headline = "";
    }

    /** What changes while the server runs; saved in the world. */
    public static final class State {
        public double price, lastMove, nudgeLeft, trend;
        public long lastUpdate, nudgeUntil, trendUntil;
        public boolean frozen;
    }

    public Map<String, Stock> stocks = new LinkedHashMap<>();
    /** Most the server pays to sellers in one day, in currency units; 0 for no cap. */
    public double dailyPayoutCap = 100_000;

    public static Stocks defaults() {
        var stocks = new Stocks();
        var yeti = new Stock();
        yeti.name = "Yeti Industries"; yeti.description = "Swings hard and keeps going"; yeti.volatility = 2; yeti.momentum = 0.3;
        yeti.shockChance = 0.5; yeti.shockSize = 15; yeti.pressure = 0.5; yeti.headline = "&e{name} moves {move}% to {price}";
        stocks.stocks.put("YETI", yeti);
        var gold = new Stock();
        gold.name = "Gold Reserve"; gold.description = "Slow, and returns to its level"; gold.start = 500; gold.floor = 100; gold.volatility = 0.3;
        gold.target = 500; gold.pull = 0.5; gold.cycleAmplitude = 2; gold.spread = 0.5;
        stocks.stocks.put("GOLD", gold);
        return stocks;
    }

    public String problem() {
        if (stocks == null || !(dailyPayoutCap >= 0 && dailyPayoutCap <= 1e12)) return "invalid stocks or dailyPayoutCap";
        for (var entry : stocks.entrySet()) {
            var s = entry.getValue();
            if (entry.getKey() == null || !entry.getKey().matches("[A-Z0-9]{1,8}") || s == null) return "tickers are 1 to 8 capital letters or digits";
            if (s.name == null || s.description == null || s.headline == null) return entry.getKey() + ": text must not be null";
            if (!(s.floor > 0 && s.floor <= s.start && s.start <= s.ceiling && s.ceiling <= 1e9)) return entry.getKey() + ": need 0 < floor <= start <= ceiling";
            if (s.updateSeconds < 1 || !(s.cycleHours > 0) || s.holdingLimit < 0) return entry.getKey() + ": updateSeconds, cycleHours and holdingLimit must be positive";
            for (double value : new double[]{s.volatility, s.pull, s.cycleAmplitude, s.momentum, s.shockChance, s.shockSize, s.pressure, s.spread}) {
                if (!(value >= 0 && value <= 100)) return entry.getKey() + ": rates must be between 0 and 100";
            }
            if (!(Math.abs(s.drift) <= 1000) || !(s.target >= 0) || s.momentum >= 1 || s.pull > 1 || s.shockChance > 1) return entry.getKey() + ": drift, target, momentum, pull or shockChance out of range";
        }
        return null;
    }

    /** Advances one update and returns the size of a shock, or 0 when there was none. */
    public static double step(Stock stock, State state, long now, RandomGenerator random) {
        double days = stock.updateSeconds / 86_400.0;
        double move = (random.nextDouble() * 2 - 1) * stock.volatility + stock.momentum * state.lastMove + stock.drift * days;
        if (stock.target > 0) move += stock.pull * days * (stock.target - state.price) / state.price * 100;
        if (stock.cycleAmplitude > 0) {
            double period = stock.cycleHours * 3_600_000, before = now - stock.updateSeconds * 1000.0;
            move += stock.cycleAmplitude * (Math.sin(2 * Math.PI * now / period) - Math.sin(2 * Math.PI * before / period));
        }
        state.lastMove = move;
        if (now < state.trendUntil) move += state.trend * days;
        if (state.nudgeLeft != 0) {
            // A gradual nudge spreads what is left evenly over the updates that remain.
            long updatesLeft = Math.max(1, (state.nudgeUntil - now) / (stock.updateSeconds * 1000L) + 1);
            double part = state.nudgeLeft / updatesLeft;
            move += part;
            state.nudgeLeft = updatesLeft == 1 ? 0 : state.nudgeLeft - part;
        }
        double shock = 0;
        if (stock.shockSize > 0 && random.nextDouble() < stock.shockChance * days) {
            shock = random.nextBoolean() ? stock.shockSize : -stock.shockSize;
            move += shock;
        }
        state.price = clamp(stock, state.price * (1 + move / 100));
        state.lastUpdate = now;
        return shock;
    }

    /** Moves the price for a trade first, so the trader always gets the worse, post-trade price. */
    public static void pressure(Stock stock, State state, long shares, boolean buy) {
        double move = stock.pressure * shares / 100;
        state.price = clamp(stock, state.price * (1 + (buy ? move : -move) / 100));
    }

    public static double clamp(Stock stock, double price) {
        return Math.max(stock.floor, Math.min(stock.ceiling, price));
    }

    /** Cents per share: buyers pay half the spread above the price, sellers get half below, each rounded against the trader. */
    public static long quote(Stock stock, State state, boolean buy) {
        double price = state.price * (1 + (buy ? stock.spread : -stock.spread) / 200) * 100;
        return Math.max(1, buy ? (long) Math.ceil(price) : (long) Math.floor(price));
    }
}
