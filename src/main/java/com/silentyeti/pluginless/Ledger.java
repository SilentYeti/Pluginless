package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Ledger {
    private static final Codec<Map<UUID, Long>> BALANCES =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG).xmap(HashMap::new, map -> map);

    public record Transaction(long time, String actor, String from, String to, long amount, String reason) {
        static final Codec<Transaction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("time").forGetter(Transaction::time),
                Codec.STRING.fieldOf("actor").forGetter(Transaction::actor),
                Codec.STRING.fieldOf("from").forGetter(Transaction::from),
                Codec.STRING.fieldOf("to").forGetter(Transaction::to),
                Codec.LONG.fieldOf("amount").forGetter(Transaction::amount),
                Codec.STRING.fieldOf("reason").forGetter(Transaction::reason)
        ).apply(instance, Transaction::new));
    }

    public static Accounts of(MinecraftServer server) {
        return of(server, "server", "");
    }

    static Accounts of(MinecraftServer server, String actor, String reason) {
        var store = Store.open(server, "ledger", BALANCES, HashMap::new);
        var log = log(server);
        return new Accounts(store.value, Config.startBalanceCents(), store::setDirty, movement -> {
            log.value.add(new Transaction(System.currentTimeMillis(), actor, endpoint(movement.from(), "mint"),
                    endpoint(movement.to(), "destroyed"), movement.amount(), reason.isEmpty() ? movement.reason() : reason + ":" + movement.reason()));
            log.setDirty();
        }, Bank.income(server));
    }

    static void obligation(MinecraftServer server, UUID player, long cents, String reason) {
        var log = log(server);
        log.value.add(new Transaction(System.currentTimeMillis(), "server", endpoint(player, ""), "server", cents, reason));
        log.setDirty();
    }

    private static String endpoint(UUID id, String absent) {
        return id == null ? absent : id.equals(Accounts.SERVER) ? "server" : id.toString();
    }

    // shortcut: history grows in the world save, move it to an indexed store when bank history becomes large
    private static Store<List<Transaction>> log(MinecraftServer server) {
        return Store.open(server, "transactions", Transaction.CODEC.listOf().xmap(ArrayList::new, list -> list), ArrayList::new);
    }

    static List<Transaction> history(MinecraftServer server, UUID account) {
        String id = endpoint(account, "");
        return log(server).value.stream().filter(t -> t.from().equals(id) || t.to().equals(id)).toList();
    }

    /** All future taxes and fees use this transfer rather than destroying money. */
    static boolean collect(MinecraftServer server, UUID player, long cents, String reason) {
        return of(server, player.toString(), reason).transfer(player, Accounts.SERVER, cents);
    }

    static boolean payout(MinecraftServer server, UUID player, long cents, String reason) {
        var accounts = of(server, "server", reason);
        // Issued money is logged as coming from "mint", so it stays visible in the history.
        if (Config.serverUnlimited() && accounts.balance(Accounts.SERVER) < cents) return accounts.deposit(player, cents);
        return accounts.transfer(Accounts.SERVER, player, cents);
    }
}
