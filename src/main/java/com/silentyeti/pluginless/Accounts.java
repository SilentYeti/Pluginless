package com.silentyeti.pluginless;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Balances in cents. Plain Java so the money rules can be unit-tested without Minecraft. */
public final class Accounts {
    private final Map<UUID, Long> balances;
    private final long startBalance;
    private final Runnable onChange;

    public Accounts(Map<UUID, Long> balances, long startBalance, Runnable onChange) {
        this.balances = balances;
        this.startBalance = startBalance;
        this.onChange = onChange;
    }

    public long balance(UUID id) {
        return balances.getOrDefault(id, startBalance);
    }

    /** True once the player has joined. */
    public boolean exists(UUID id) {
        return balances.containsKey(id);
    }

    /** Called when a player joins, so they can be paid and show up in the ranking. */
    public void open(UUID id) {
        if (!exists(id)) set(id, startBalance);
    }

    public boolean set(UUID id, long amount) {
        if (amount < 0) return false;
        balances.put(id, amount);
        onChange.run();
        return true;
    }

    public boolean deposit(UUID id, long amount) {
        long current = balance(id);
        // second check rejects overflow past Long.MAX_VALUE
        if (amount <= 0 || current + amount < current) return false;
        return set(id, current + amount);
    }

    public boolean withdraw(UUID id, long amount) {
        long current = balance(id);
        if (amount <= 0 || amount > current) return false;
        return set(id, current - amount);
    }

    public boolean transfer(UUID from, UUID to, long amount) {
        if (from.equals(to)) return false;
        long target = balance(to);
        if (amount <= 0 || target + amount < target) return false;
        return withdraw(from, amount) && deposit(to, amount);
    }

    /** Richest first. */
    public List<Map.Entry<UUID, Long>> top(int count) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(count)
                .toList();
    }
}
