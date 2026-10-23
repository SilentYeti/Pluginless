package com.silentyeti.pluginless;

import java.util.UUID;
import java.util.function.LongPredicate;

/** The short-selling rules, in cents and ratios, with no server in sight so they can be tested alone. */
public final class ShortBook {
    /** entry is what the short sale fetched; deposit is the security still held; updated is when the fee was last charged. */
    public record Position(String ticker, long shares, long entry, long deposit, long updated) {}

    /** What closing pays out: the deposit is split between the player and the server, the server pays any profit, and a loss beyond the deposit stays owed. */
    public record Settlement(long toPlayer, long toServer, long profit, long shortfall) {}

    /** The security asked for a position worth this much, at least one cent. */
    public static long requiredDeposit(long entry, double ratio) {
        return Math.max(1, (long) Math.ceil(entry * ratio - 1e-6));
    }

    /** Positive when the price fell: what the sale fetched less what covering costs. */
    public static long pnl(long entry, long cover) { return entry - cover; }

    /** True once the loss has used up this share of the deposit; a position with no deposit left is always over. */
    public static boolean shouldClose(long deposit, long pnl, double closeRatio) {
        return deposit <= 0 || pnl < 0 && -pnl >= (long) Math.ceil(deposit * closeRatio - 1e-6);
    }

    /** The fee for keeping a position open: a daily percentage of its entry value, pro rata. */
    public static long fee(long entry, double dailyPercent, long elapsedMillis) {
        if (elapsedMillis <= 0 || dailyPercent <= 0) return 0;
        return Math.round(entry * dailyPercent / 100 * elapsedMillis / 86_400_000.0);
    }

    public static Settlement settle(long deposit, long pnl) {
        if (pnl >= 0) return new Settlement(deposit, 0, pnl, 0);
        long loss = -pnl, lost = Math.min(deposit, loss);
        return new Settlement(deposit - lost, lost, 0, loss - lost);
    }

    /**
     * Moves the settlement money all-or-nothing: the reversible escrow releases first, the one step that cannot be undone (the profit payout or the debt record) last.
     * When any step fails, what already moved goes back and false is returned, so the position stays and the close can be retried.
     */
    public static boolean pay(Accounts accounts, UUID player, Settlement settlement, LongPredicate payout, LongPredicate debt) {
        long toServer = settlement.toServer(), toPlayer = settlement.toPlayer();
        if (toServer > 0 && !accounts.release(Accounts.ESCROW, Accounts.SERVER, toServer)) return false;
        if (toPlayer > 0 && !accounts.release(Accounts.ESCROW, player, toPlayer)) {
            if (toServer > 0) accounts.release(Accounts.SERVER, Accounts.ESCROW, toServer);
            return false;
        }
        boolean last = settlement.profit() > 0 ? payout.test(settlement.profit()) : settlement.shortfall() <= 0 || debt.test(settlement.shortfall());
        if (last) return true;
        if (toPlayer > 0) accounts.release(player, Accounts.ESCROW, toPlayer);
        if (toServer > 0) accounts.release(Accounts.SERVER, Accounts.ESCROW, toServer);
        return false;
    }

    private ShortBook() {}
}
