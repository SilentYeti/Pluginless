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

/** Server loans against admin stock holdings. Separate from the bank loan; a shortfall after a forced sale becomes bank debt. */
public final class Margin {
    private static final Codec<MarginBook.Loan> LOAN = RecordCodecBuilder.create(instance -> instance.group(
            Bank.DEBT.fieldOf("debt").forGetter(MarginBook.Loan::debt),
            Codec.BOOL.fieldOf("warned").forGetter(MarginBook.Loan::warned),
            Codec.LONG.optionalFieldOf("proceeds").forGetter(MarginBook.Loan::proceeds)
    ).apply(instance, MarginBook.Loan::new));

    static Store<Map<UUID, MarginBook.Loan>> loans(MinecraftServer server) {
        return Store.open(server, "margin", Codec.unboundedMap(UUIDUtil.STRING_CODEC, LOAN).xmap(HashMap::new, map -> map), HashMap::new);
    }

    static double multiple(MinecraftServer server, UUID player) {
        return Math.max(1, RankGate.ranks.number(RankGate.rankOf(server, player), "margin_multiple", Config.marginMultiple()));
    }

    static double interest(MinecraftServer server, UUID player) {
        return Math.max(0, RankGate.ranks.number(RankGate.rankOf(server, player), "margin_interest", Config.marginInterest()));
    }

    /** Owed now, interest included. */
    public static long loan(MinecraftServer server, UUID player) {
        var loan = accrue(server, player, System.currentTimeMillis());
        return loan == null ? 0 : loan.debt().amount();
    }

    private static MarginBook.Loan accrue(MinecraftServer server, UUID player, long now) {
        var store = loans(server);
        var old = store.value.get(player);
        // A loan whose holdings are already sold waits only for its settlement: its amount is frozen.
        if (old == null || old.sold()) return old;
        // shortcut: catch-up uses the current rate, like the bank loan
        var debt = Debts.accrue(old.debt(), now, interest(server, player));
        if (debt.equals(old.debt())) return old;
        var updated = new MarginBook.Loan(debt, old.warned());
        store.value.put(player, updated);
        store.setDirty();
        Ledger.obligation(server, player, debt.amount() - old.debt().amount(), "margin.interest");
        return updated;
    }

    static long limit(MinecraftServer server, UUID player) {
        return MarginBook.maxLoan(StockExchange.value(server, player), multiple(server, player));
    }

    static boolean borrow(ServerPlayer player, long cents) {
        var server = player.level().getServer();
        if (server == null || cents <= 0) return false;
        var id = player.getUUID();
        long now = System.currentTimeMillis(), owed = loan(server, id);
        // A debtor, including one left with a shortfall, may not borrow more.
        var existing = loans(server).value.get(id);
        if (existing != null && existing.sold()) return false;
        if (Bank.debt(server, id) != 0 || owed + cents > limit(server, id) || owed + cents < owed) return false;
        if (!Ledger.payout(server, id, cents, "margin.loan")) return false;
        var old = loans(server).value.get(id);
        var debt = old == null ? new Debts.Debt(cents, cents, now) : new Debts.Debt(old.debt().amount() + cents, old.debt().principal() + cents, old.debt().updated());
        loans(server).value.put(id, new MarginBook.Loan(debt, false));
        loans(server).setDirty();
        Ledger.obligation(server, id, cents, "margin.loan");
        return true;
    }

    /** Repays up to the amount given from the balance; a failed payment changes nothing. */
    public static boolean repay(MinecraftServer server, UUID player, long cents) {
        var loan = accrue(server, player, System.currentTimeMillis());
        if (loan == null || loan.sold()) return false;
        cents = Math.min(cents, loan.debt().amount());
        if (cents <= 0 || !Ledger.collect(server, player, cents, "margin.repay")) return false;
        reduce(server, player, loan, cents);
        return true;
    }

    private static void reduce(MinecraftServer server, UUID player, MarginBook.Loan loan, long cents) {
        var store = loans(server);
        var debt = Debts.repay(loan.debt(), cents);
        if (debt.amount() == 0) store.value.remove(player);
        else store.value.put(player, new MarginBook.Loan(debt, loan.warned()));
        store.setDirty();
    }

    /** True when selling these shares would leave the loan above what the remaining holdings may carry. */
    static boolean allowsSale(MinecraftServer server, UUID player, String ticker, long shares) {
        long owed = loan(server, player);
        if (owed == 0) return true;
        var stock = StockExchange.config.stocks.get(ticker);
        if (stock == null) return false;
        long left = StockExchange.value(server, player) - Stocks.quote(stock, StockExchange.state(server, ticker), false) * shares;
        return owed <= MarginBook.maxLoan(left, multiple(server, player));
    }

    static void tick(MinecraftServer server, long now) {
        for (var player : List.copyOf(loans(server).value.keySet())) {
            var loan = accrue(server, player, now);
            if (loan == null) continue;
            if (loan.sold()) { settle(server, player, loan); continue; }
            long owed = loan.debt().amount(), holdings = StockExchange.value(server, player);
            switch (MarginBook.health(holdings, owed, Config.marginWarn(), Config.marginLiquidate())) {
                case LIQUIDATE -> liquidate(server, player, loan);
                case WARN -> {
                    if (loan.warned()) break;
                    loans(server).value.put(player, new MarginBook.Loan(loan.debt(), true));
                    loans(server).setDirty();
                    tell(server, player, Component.translatable("pluginless.margin.warning", Config.format(owed), Config.format(holdings)));
                }
                case OK -> {
                    if (!loan.warned()) break;
                    loans(server).value.put(player, new MarginBook.Loan(loan.debt(), false));
                    loans(server).setDirty();
                }
            }
        }
    }

    private static void liquidate(MinecraftServer server, UUID player, MarginBook.Loan loan) {
        long proceeds = StockExchange.sellAll(server, player);
        // Everything left is on a halted stock: wait for trading to resume rather than turning the loan into debt.
        if (proceeds == 0 && StockExchange.value(server, player) > 0) return;
        // The sale cannot be undone, so the proceeds are recorded with the loan before anything can fail.
        var sold = loan.withProceeds(proceeds);
        loans(server).value.put(player, sold);
        loans(server).setDirty();
        settle(server, player, sold);
    }

    /** Pays the surplus or records the shortfall; the loan is only dropped once that worked, otherwise the next tick tries again. */
    static void settle(MinecraftServer server, UUID player, MarginBook.Loan loan) {
        long owed = loan.debt().amount(), proceeds = loan.proceeds().orElse(0L);
        // The proceeds repay the loan directly: the server pays out what was owed and takes it back, so only the surplus moves.
        long surplus = MarginBook.surplus(owed, proceeds), missing = MarginBook.shortfall(owed, proceeds);
        if (surplus > 0 && !Ledger.payout(server, player, surplus, "margin.liquidation")) {
            Pluginless.LOGGER.warn("margin: could not pay {} surplus to {}, will retry", surplus, player);
            return;
        }
        if (missing > 0 && !Bank.addDebt(server, player, missing, "margin")) {
            Pluginless.LOGGER.warn("margin: could not record {} shortfall for {}, will retry", missing, player);
            return;
        }
        loans(server).value.remove(player);
        loans(server).setDirty();
        Pluginless.LOGGER.info("margin: liquidated {} proceeds {} loan {} shortfall {}", player, proceeds, owed, missing);
        tell(server, player, Component.translatable("pluginless.margin.liquidated", Config.format(proceeds), Config.format(owed), Config.format(missing)));
    }

    private static void tell(MinecraftServer server, UUID player, Component message) {
        var online = server.getPlayerList().getPlayer(player);
        if (online != null) online.sendSystemMessage(message);
    }

    static Component summary(MinecraftServer server, UUID player) {
        return Component.translatable("pluginless.margin.summary", Config.format(loan(server, player)), Config.format(StockExchange.value(server, player)),
                Config.format(limit(server, player)), multiple(server, player), interest(server, player), Config.marginWarn(), Config.marginLiquidate());
    }

    private Margin() {}
}
