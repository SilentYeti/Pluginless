package com.silentyeti.pluginless;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cents, simple daily interest on unpaid principal, and interest-first repayment. */
public final class Debts {
    public static final long DAY = 86_400_000;
    public record Debt(long amount, long principal, long updated) {
        public Debt {
            if (amount < 0 || principal < 0 || principal > amount || updated < 0) throw new IllegalArgumentException("invalid debt");
        }
    }

    public static Debt accrue(Debt debt, long now, double percent) {
        if (!Double.isFinite(percent) || percent < 0) throw new IllegalArgumentException("invalid interest");
        long days = Math.max(0, (now - debt.updated()) / DAY);
        if (days == 0) return debt;
        var daily = BigDecimal.valueOf(debt.principal()).multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING);
        var interest = daily.multiply(BigDecimal.valueOf(days));
        // shortcut: interest saturates at the cents limit, use a larger monetary type before debts can exceed it
        long added = interest.min(BigDecimal.valueOf(Long.MAX_VALUE - debt.amount())).longValueExact();
        return new Debt(debt.amount() + added, debt.principal(), debt.updated() + days * DAY);
    }

    public static Debt repay(Debt debt, long cents) {
        if (cents <= 0 || cents > debt.amount()) throw new IllegalArgumentException("invalid repayment");
        long amount = debt.amount() - cents;
        return new Debt(amount, Math.min(debt.principal(), amount), debt.updated());
    }

    private Debts() {}
}
