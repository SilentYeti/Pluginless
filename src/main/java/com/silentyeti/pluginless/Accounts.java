package com.silentyeti.pluginless;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Balances in cents. Plain Java so the money rules can be unit-tested without Minecraft. */
public final class Accounts {
    public static final UUID SERVER = new UUID(0, 0);
    /** Holds the money behind open market orders; not a player, and never income. */
    public static final UUID ESCROW = new UUID(0, 1);

    /** The server, escrow and company accounts: they start empty and are not players. */
    public static boolean system(UUID id) {
        return id.getMostSignificantBits() == 0;
    }
    public record Movement(UUID from, UUID to, long amount, String reason) {}
    public interface Income {
        long withheld(UUID player, long cents);
        void repaid(UUID player, long cents);
    }
    private static final Income NO_DEBT = new Income() {
        public long withheld(UUID player, long cents) { return 0; }
        public void repaid(UUID player, long cents) {}
    };
    private final Income income;
    private final Consumer<Movement> onMovement;
    private final Map<UUID, Long> balances;
    private final long startBalance;
    private final Runnable onChange;

    public Accounts(Map<UUID, Long> balances, long startBalance, Runnable onChange) {
        this(balances, startBalance, onChange, movement -> {});
    }

    public Accounts(Map<UUID, Long> balances, long startBalance, Runnable onChange, Consumer<Movement> onMovement) {
        this(balances, startBalance, onChange, onMovement, NO_DEBT);
    }

    public Accounts(Map<UUID, Long> balances, long startBalance, Runnable onChange, Consumer<Movement> onMovement, Income income) {
        this.income = income;
        this.onMovement = onMovement;
        this.balances = balances;
        this.startBalance = startBalance;
        this.onChange = onChange;
    }

    public long balance(UUID id) {
        return balances.getOrDefault(id, system(id) ? 0L : startBalance);
    }

    /** True once an account has been opened or received money. */
    public boolean exists(UUID id) {
        return balances.containsKey(id);
    }

    /** Opening an account twice never resets it. */
    public void open(UUID id) {
        if (!exists(id)) {
            long initial = system(id) ? 0 : startBalance;
            balances.put(id, initial);
            onChange.run();
            if (initial > 0) onMovement.accept(new Movement(null, id, initial, "account.open"));
        }
    }

    public boolean set(UUID id, long amount) {
        if (amount < 0) return false;
        long before = balance(id);
        long added = Math.max(0, amount - before);
        long withheld = income.withheld(id, added);
        if (withheld > 0 && Long.MAX_VALUE - balance(SERVER) < withheld) return false;
        open(id);
        balances.put(id, amount - withheld);
        if (withheld > 0) balances.put(SERVER, balance(SERVER) + withheld);
        onChange.run();
        if (amount != before) onMovement.accept(new Movement(amount > before ? null : id,
                amount > before ? id : null, Math.abs(amount - before), "set"));
        repayIncome(id, withheld);
        return true;
    }

    public boolean deposit(UUID id, long amount) {
        if (amount <= 0) return false;
        long current = balance(id);
        long withheld = income.withheld(id, amount);
        if (Long.MAX_VALUE - current < amount - withheld || withheld > 0 && Long.MAX_VALUE - balance(SERVER) < withheld) return false;
        open(id);
        balances.put(id, current + (amount - withheld));
        if (withheld > 0) balances.put(SERVER, balance(SERVER) + withheld);
        onChange.run();
        onMovement.accept(new Movement(null, id, amount, "deposit"));
        repayIncome(id, withheld);
        return true;
    }

    public boolean withdraw(UUID id, long amount) {
        long current = balance(id);
        if (amount <= 0 || amount > current) return false;
        open(id);
        balances.put(id, current - amount);
        onChange.run();
        onMovement.accept(new Movement(id, null, amount, "withdraw"));
        return true;
    }

    public boolean transfer(UUID from, UUID to, long amount) {
        return transfer(from, to, amount, true);
    }

    /** Moves a player's own money into or out of escrow, or a fee to the server: never garnished as income. */
    public boolean release(UUID from, UUID to, long amount) {
        return transfer(from, to, amount, false);
    }

    /** A returned server fee is not income. */
    public boolean refund(UUID player, long amount) {
        return transfer(SERVER, player, amount, false);
    }

    private boolean transfer(UUID from, UUID to, long amount, boolean garnish) {
        if (from.equals(to) || amount <= 0) return false;
        long sender = balance(from);
        if (amount > sender) return false;
        long withheld = garnish ? income.withheld(to, amount) : 0;
        long target = balance(to);
        long serverAfter = balance(SERVER) - (from.equals(SERVER) ? amount : 0);
        if (Long.MAX_VALUE - target < amount - withheld || withheld > 0 && Long.MAX_VALUE - serverAfter < withheld) return false;
        open(from);
        open(to);
        balances.put(from, sender - amount);
        balances.put(to, target + (amount - withheld));
        if (withheld > 0) balances.put(SERVER, serverAfter + withheld);
        onChange.run();
        onMovement.accept(new Movement(from, to, amount, "transfer"));
        repayIncome(to, withheld);
        return true;
    }

    private void repayIncome(UUID player, long cents) {
        if (cents == 0) return;
        income.repaid(player, cents);
        onMovement.accept(new Movement(player, SERVER, cents, "debt.income"));
    }

    /** Carries a provisional never-seen account to the player's joining UUID. */
    public boolean move(UUID from, UUID to) {
        if (from.equals(to) || !exists(from)) return true;
        long amount = balances.get(from);
        long target = balances.getOrDefault(to, 0L);
        if (Long.MAX_VALUE - target < amount) return false;
        balances.remove(from);
        balances.put(to, target + amount);
        onChange.run();
        if (amount > 0) onMovement.accept(new Movement(from, to, amount, "identity"));
        return true;
    }

    /** Richest first; the server is not a player. */
    public List<Map.Entry<UUID, Long>> top(int count) {
        return balances.entrySet().stream()
                .filter(entry -> !system(entry.getKey()))
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(count)
                .toList();
    }
}
