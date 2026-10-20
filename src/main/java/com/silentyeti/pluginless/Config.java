package com.silentyeti.pluginless;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.ConfigValue<String> CURRENCY_SYMBOL = BUILDER
            .comment("Shown in front of every amount")
            .define("currencySymbol", "$");
    private static final ModConfigSpec.DoubleValue START_BALANCE = BUILDER
            .comment("Balance of a player who has never had money")
            .defineInRange("startBalance", 100.0, 0.0, 1e12);

    private static final ModConfigSpec.IntValue TELEPORT_WARMUP = BUILDER.defineInRange("teleportWarmupSeconds", 3, 0, 3600);
    private static final ModConfigSpec.IntValue TELEPORT_REQUEST = BUILDER.defineInRange("teleportRequestSeconds", 60, 1, 3600);
    private static final ModConfigSpec.IntValue RTP_RADIUS = BUILDER.defineInRange("rtpRadius", 5000, 1, 1_000_000);
    private static final ModConfigSpec.IntValue RTP_ATTEMPTS = BUILDER.defineInRange("rtpAttempts", 16, 1, 128);
    private static final ModConfigSpec.IntValue NICK_LENGTH = BUILDER.defineInRange("nickMaxLength", 24, 1, 32);
    private static final ModConfigSpec.DoubleValue SKULL_PRICE = BUILDER.defineInRange("skullPrice", 0.0, 0.0, 1e15);
    private static final ModConfigSpec.IntValue PAYMENT_REQUEST = BUILDER.defineInRange("paymentRequestSeconds", 120, 1, 3600);
    private static final ModConfigSpec.DoubleValue LOAN_LIMIT = BUILDER.defineInRange("loanLimit", 1000.0, 0.0, 1e15);
    private static final ModConfigSpec.DoubleValue LOAN_INTEREST = BUILDER.defineInRange("loanDailyInterest", 1.0, 0.0, 100.0);

    private static final ModConfigSpec.DoubleValue MARGIN_MULTIPLE = BUILDER.comment("Stocks you may control per unit of your own money; 1 disables margin loans").defineInRange("marginMultiple", 2.0, 1.0, 100.0);
    private static final ModConfigSpec.DoubleValue MARGIN_INTEREST = BUILDER.comment("Percent per day on the margin loan").defineInRange("marginDailyInterest", 1.0, 0.0, 100.0);
    private static final ModConfigSpec.DoubleValue SHORT_DEPOSIT = BUILDER.comment("Security a short seller puts up, as a share of the position's value").defineInRange("shortDepositRatio", 0.5, 0.01, 10.0);
    private static final ModConfigSpec.DoubleValue SHORT_CLOSE = BUILDER.comment("A short is closed when losses use up this share of its security").defineInRange("shortCloseRatio", 0.9, 0.1, 1.0);
    private static final ModConfigSpec.DoubleValue SHORT_FEE = BUILDER.comment("Percent of the position's value charged per day to keep a short open").defineInRange("shortDailyFeePercent", 0.5, 0.0, 100.0);
    private static final ModConfigSpec.DoubleValue MARGIN_WARN = BUILDER.comment("Warn when holdings fall below this multiple of the loan").defineInRange("marginWarnRatio", 1.25, 1.0, 100.0);
    private static final ModConfigSpec.DoubleValue MARGIN_LIQUIDATE = BUILDER.comment("Sell everything and repay when holdings fall below this multiple of the loan").defineInRange("marginLiquidateRatio", 1.10, 1.0, 100.0);

    private static final ModConfigSpec.BooleanValue SERVER_UNLIMITED = BUILDER
            .comment("When the server account cannot cover a payout (income, loans), issue new money instead of refusing")
            .define("serverAccountUnlimited", true);
    private static final ModConfigSpec.IntValue PAYCHECK_MINUTES = BUILDER.comment("0 disables paychecks").defineInRange("paycheckMinutes", 30, 0, 1440);
    private static final ModConfigSpec.DoubleValue PAYCHECK = BUILDER.defineInRange("paycheckAmount", 50.0, 0.0, 1e12);
    private static final ModConfigSpec.DoubleValue DAILY_REWARD = BUILDER.comment("0 disables the daily login reward").defineInRange("dailyReward", 25.0, 0.0, 1e12);
    private static final ModConfigSpec.DoubleValue DAILY_STREAK = BUILDER.comment("Added per consecutive day after the first").defineInRange("dailyStreakBonus", 5.0, 0.0, 1e12);
    private static final ModConfigSpec.IntValue DAILY_STREAK_MAX = BUILDER.defineInRange("dailyStreakMaxDays", 7, 1, 3650);
    private static final ModConfigSpec.IntValue MOB_CAP = BUILDER.comment("Paid kills per player per hour").defineInRange("mobRewardHourlyCap", 100, 0, 1_000_000);
    private static final ModConfigSpec.DoubleValue TRANSFER_TAX = BUILDER.comment("Percent charged to the sender on /pay").defineInRange("transferTaxPercent", 0.0, 0.0, 100.0);
    private static final ModConfigSpec.DoubleValue AUCTION_TAX = BUILDER.comment("Percent of the sale price kept from the seller").defineInRange("auctionSalesTaxPercent", 2.0, 0.0, 100.0);
    private static final ModConfigSpec.IntValue AUCTION_HOURS = BUILDER.defineInRange("auctionListingHours", 48, 1, 8760);
    private static final ModConfigSpec.IntValue AUCTION_GRACE = BUILDER.comment("Seconds after listing before anyone can buy").defineInRange("auctionGraceSeconds", 20, 0, 3600);
    private static final ModConfigSpec.DoubleValue AUCTION_RAISE = BUILDER.comment("Percent a bid must beat the current bid by").defineInRange("auctionMinRaisePercent", 5.0, 0.0, 100.0);
    private static final ModConfigSpec.IntValue AUCTION_SLOTS = BUILDER.defineInRange("auctionSlots", 14, 0, 1000);
    private static final ModConfigSpec.DoubleValue AUCTION_MIN = BUILDER.defineInRange("auctionMinPrice", 0.01, 0.01, 1e12);
    private static final ModConfigSpec.DoubleValue AUCTION_MAX = BUILDER.defineInRange("auctionMaxPrice", 1e9, 0.01, 1e12);

    private static final ModConfigSpec.DoubleValue MARKET_FEE = BUILDER.comment("Percent of each market trade kept from the seller").defineInRange("marketFeePercent", 1.25, 0.0, 100.0);
    private static final ModConfigSpec.IntValue MARKET_ORDERS = BUILDER.defineInRange("marketOrders", 20, 0, 1000);
    static double marketFee() { return MARKET_FEE.get(); }
    static int marketOrders() { return MARKET_ORDERS.get(); }
    private static final ModConfigSpec.DoubleValue COMPANY_FEE = BUILDER.defineInRange("companyFee", 500.0, 0.0, 1e12);
    private static final ModConfigSpec.IntValue COMPANIES = BUILDER.comment("Companies one player may own").defineInRange("companiesPerPlayer", 1, 0, 100);
    private static final ModConfigSpec.IntValue SHOPS = BUILDER.defineInRange("shopsPerCompany", 3, 0, 100);
    private static final ModConfigSpec.IntValue SHOP_STORAGE = BUILDER.comment("Items a shop can hold").defineInRange("shopStorage", 3456, 0, 10_000_000);
    private static final ModConfigSpec.DoubleValue SHOP_TAX = BUILDER.comment("Percent of each shop sale paid to the server").defineInRange("shopSalesTaxPercent", 2.0, 0.0, 100.0);
    private static final ModConfigSpec.IntValue PAYROLL_HOURS = BUILDER.comment("0 disables company payroll").defineInRange("payrollHours", 24, 0, 8760);
    static long companyFeeCents() { return Math.round(COMPANY_FEE.get() * 100); }
    static int companies() { return COMPANIES.get(); }
    static int shopsPerCompany() { return SHOPS.get(); }
    static int shopStorage() { return SHOP_STORAGE.get(); }
    static double shopTax() { return SHOP_TAX.get(); }
    static long payrollMillis() { return PAYROLL_HOURS.get() * 3_600_000L; }
    private static final ModConfigSpec.BooleanValue BANKRUPTCY = BUILDER.comment("A company that cannot pay a wage on payday goes bankrupt").define("bankruptcyOnUnpaidWages", true);
    private static final ModConfigSpec.DoubleValue SHARES_FOUNDER = BUILDER.comment("Optional protection: the owner cannot sell below this percent of the shares; 0 is off").defineInRange("sharesFounderMinPercent", 0.0, 0.0, 100.0);
    private static final ModConfigSpec.IntValue SHARES_LOCKUP = BUILDER.comment("Optional protection: hours after issuing shares during which money cannot be withdrawn; 0 is off").defineInRange("sharesLockupHours", 0, 0, 8760);
    private static final ModConfigSpec.DoubleValue SHARES_MOVE = BUILDER.comment("Optional protection: orders must be within this percent of the last trade; 0 is off").defineInRange("sharesPriceMoveLimitPercent", 0.0, 0.0, 1000.0);
    static boolean bankruptcy() { return BANKRUPTCY.get(); }
    static double sharesFounderMin() { return SHARES_FOUNDER.get(); }
    static long sharesLockupMillis() { return SHARES_LOCKUP.get() * 3_600_000L; }
    static double sharesMoveLimit() { return SHARES_MOVE.get(); }
    static boolean serverUnlimited() { return SERVER_UNLIMITED.get(); }
    static long paycheckSeconds() { return PAYCHECK_MINUTES.get() * 60L; }
    static double paycheck() { return PAYCHECK.get(); }
    static double dailyReward() { return DAILY_REWARD.get(); }
    static double dailyStreakBonus() { return DAILY_STREAK.get(); }
    static int dailyStreakMax() { return DAILY_STREAK_MAX.get(); }
    static int mobRewardCap() { return MOB_CAP.get(); }
    static double transferTax() { return TRANSFER_TAX.get(); }
    static double auctionTax() { return AUCTION_TAX.get(); }
    static long auctionMillis() { return AUCTION_HOURS.get() * 3_600_000L; }
    static long auctionGraceMillis() { return AUCTION_GRACE.get() * 1000L; }
    static double auctionRaise() { return AUCTION_RAISE.get(); }
    static int auctionSlots() { return AUCTION_SLOTS.get(); }
    static long auctionMinCents() { return Math.round(AUCTION_MIN.get() * 100); }
    static long auctionMaxCents() { return Math.round(AUCTION_MAX.get() * 100); }
    static int teleportWarmupSeconds() { return TELEPORT_WARMUP.get(); }
    static long teleportRequestMillis() { return TELEPORT_REQUEST.get() * 1000L; }
    static int rtpRadius() { return RTP_RADIUS.get(); }
    static int rtpAttempts() { return RTP_ATTEMPTS.get(); }
    static int nickMaxLength() { return NICK_LENGTH.get(); }
    static long skullPriceCents() { return Math.round(SKULL_PRICE.get() * 100); }
    static long paymentRequestMillis() { return PAYMENT_REQUEST.get() * 1000L; }
    static double loanLimit() { return LOAN_LIMIT.get(); }
    static double marginMultiple() { return MARGIN_MULTIPLE.get(); }
    static double shortDeposit() { return SHORT_DEPOSIT.get(); }
    static double shortClose() { return SHORT_CLOSE.get(); }
    static double shortFee() { return SHORT_FEE.get(); }
    static double marginInterest() { return MARGIN_INTEREST.get(); }
    static double marginWarn() { return Math.max(MARGIN_WARN.get(), MARGIN_LIQUIDATE.get()); }
    static double marginLiquidate() { return MARGIN_LIQUIDATE.get(); }
    static double loanDailyInterest() { return LOAN_INTEREST.get(); }

    static final ModConfigSpec SPEC = BUILDER.build();

    static long startBalanceCents() {
        return Math.round(START_BALANCE.get() * 100);
    }

    public static String format(long cents) {
        return "%s%,d.%02d".formatted(CURRENCY_SYMBOL.get(), cents / 100, cents % 100);
    }
}
