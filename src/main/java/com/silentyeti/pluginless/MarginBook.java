package com.silentyeti.pluginless;

/** The margin rules, in cents and ratios, with no server in sight so they can be tested alone. */
public final class MarginBook {
    /** A loan is a plain debt plus whether the player has already been warned about it. */
    public record Loan(Debts.Debt debt, boolean warned) {}

    public enum Health { OK, WARN, LIQUIDATE }

    /** The most that may be owed against these holdings: at multiple 2 the loan is half the holdings, so 1000 of your own controls 2000. */
    public static long maxLoan(long holdings, double multiple) {
        if (holdings <= 0 || !(multiple > 1)) return 0;
        return (long) Math.floor(holdings * (multiple - 1) / multiple);
    }

    /** Holdings as a multiple of the loan decide the state; both fractions are ratios such as 1.25. */
    public static Health health(long holdings, long loan, double warn, double liquidate) {
        if (loan <= 0) return Health.OK;
        if (holdings < threshold(loan, liquidate)) return Health.LIQUIDATE;
        return holdings < threshold(loan, warn) ? Health.WARN : Health.OK;
    }

    /** Whole cents the holdings must reach; the small allowance keeps 100 * 1.1 from becoming 110.00000000000001. */
    private static long threshold(long loan, double ratio) { return (long) Math.ceil(loan * ratio - 1e-6); }

    /** What is still owed after the proceeds of a forced sale have repaid the loan. */
    public static long shortfall(long loan, long proceeds) { return Math.max(0, loan - proceeds); }

    /** What the player keeps after the loan has been repaid. */
    public static long surplus(long loan, long proceeds) { return Math.max(0, proceeds - loan); }

    private MarginBook() {}
}
