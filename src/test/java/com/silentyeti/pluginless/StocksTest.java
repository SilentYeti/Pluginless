package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StocksTest {
    private static Stocks.Stock flat() {
        var stock = new Stocks.Stock();
        stock.volatility = 0;
        stock.updateSeconds = 86_400;
        return stock;
    }

    private static Stocks.State at(double price) {
        var state = new Stocks.State();
        state.price = price;
        return state;
    }

    @Test
    void driftAndReturnToLevelMoveAsConfigured() {
        var stock = flat(); stock.drift = 5;
        var state = at(100);
        Stocks.step(stock, state, 0, new Random(1));
        assertEquals(105, state.price, 1e-9);
        stock.drift = 0; stock.target = 200; stock.pull = 0.5;
        state = at(100);
        Stocks.step(stock, state, 0, new Random(1));
        assertEquals(150, state.price, 1e-9);
    }

    @Test
    void momentumCarriesThePreviousMoveAndTheBoundsHold() {
        var stock = flat(); stock.momentum = 0.5;
        var state = at(100); state.lastMove = 10;
        Stocks.step(stock, state, 0, new Random(1));
        assertEquals(105, state.price, 1e-9);
        stock.momentum = 0; stock.drift = 1000; stock.ceiling = 120;
        Stocks.step(stock, state, 0, new Random(1));
        assertEquals(120, state.price, 1e-9);
        stock.drift = -1000;
        Stocks.step(stock, state, 0, new Random(1));
        assertEquals(1, state.price, 1e-9);
    }

    @Test
    void randomMovesStayWithinVolatility() {
        var stock = new Stocks.Stock(); stock.volatility = 2;
        var random = new Random(7);
        for (int i = 0; i < 500; i++) {
            var state = at(100);
            Stocks.step(stock, state, i * 60_000L, random);
            assertTrue(Math.abs(state.price - 100) <= 2 + 1e-9);
        }
    }

    @Test
    void aGradualNudgeAddsUpToItsSizeAndAnInstantOneLandsAtOnce() {
        var stock = flat(); stock.updateSeconds = 60;
        var state = at(100); state.nudgeLeft = 10; state.nudgeUntil = 4 * 60_000;
        double product = 1;
        for (int i = 0; i <= 4; i++) { double before = state.price; Stocks.step(stock, state, i * 60_000L, new Random(1)); product *= state.price / before; }
        assertEquals(0, state.nudgeLeft, 1e-9);
        assertEquals(Math.pow(1.02, 5), product, 1e-9);
        state = at(100); state.nudgeLeft = -10;
        Stocks.step(stock, state, 0, new Random(1));
        assertEquals(90, state.price, 1e-9);
    }

    @Test
    void trendsEndAndShocksFireAtTheirChance() {
        var stock = flat(); stock.shockChance = 1; stock.shockSize = 20;
        var state = at(100); state.trend = 10; state.trendUntil = 1;
        double shock = Stocks.step(stock, state, 0, new Random(1));
        assertEquals(20, Math.abs(shock), 1e-9);
        assertEquals(100 * (1 + (10 + shock) / 100), state.price, 1e-9);
        stock.shockChance = 0;
        state = at(100); state.trend = 10; state.trendUntil = 1;
        assertEquals(0, Stocks.step(stock, state, 5, new Random(1)));
        assertEquals(100, state.price, 1e-9);
    }

    @Test
    void tradingAlwaysCostsTheSpreadAndThePressure() {
        var stock = flat(); stock.spread = 2; stock.pressure = 1;
        var state = at(100);
        Stocks.pressure(stock, state, 100, true);
        long paid = Stocks.quote(stock, state, true);
        Stocks.pressure(stock, state, 100, false);
        long received = Stocks.quote(stock, state, false);
        assertEquals(102_01, paid);
        assertTrue(received < paid);
        assertEquals(98_99, received);
    }

    @Test
    void theDefaultsAreValidAndBadConfigIsNamed() {
        assertNull(Stocks.defaults().problem());
        var stocks = Stocks.defaults();
        stocks.stocks.get("YETI").floor = 500;
        assertTrue(stocks.problem().startsWith("YETI"));
        stocks = Stocks.defaults();
        stocks.stocks.put("bad ticker", new Stocks.Stock());
        assertNotNull(stocks.problem());
    }
}
