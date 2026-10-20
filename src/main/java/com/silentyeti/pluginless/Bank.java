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
import java.util.WeakHashMap;

/** Server-thread debt API. Every income credit is garnished by Accounts before it becomes spendable. */
public final class Bank {
    static final Codec<Debts.Debt> DEBT = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("amount").forGetter(Debts.Debt::amount),
            Codec.LONG.fieldOf("principal").forGetter(Debts.Debt::principal),
            Codec.LONG.fieldOf("updated").forGetter(Debts.Debt::updated)
    ).apply(instance, Debts.Debt::new));
    private static final Map<MinecraftServer, Requests<Long>> REQUESTS = new WeakHashMap<>();

    private static Store<Map<UUID, Debts.Debt>> debts(MinecraftServer server) {
        return Store.open(server, "debts", Codec.unboundedMap(UUIDUtil.STRING_CODEC, DEBT).xmap(HashMap::new, map -> map), HashMap::new);
    }

    static Requests<Long> requests(MinecraftServer server) {
        return REQUESTS.computeIfAbsent(server, key -> new Requests<>());
    }

    public static long debt(MinecraftServer server, UUID player) {
        return accrue(server, player, System.currentTimeMillis()).amount();
    }

    private static Debts.Debt accrue(MinecraftServer server, UUID player, long now) {
        var store = debts(server);
        var old = store.value.getOrDefault(player, new Debts.Debt(0, 0, now));
        // shortcut: catch-up uses the current rate, save rate history if historical rates must survive changes
        var updated = Debts.accrue(old, now, Config.loanDailyInterest());
        if (!old.equals(updated)) {
            store.value.put(player, updated);
            store.setDirty();
            if (updated.amount() > old.amount()) Ledger.obligation(server, player, updated.amount() - old.amount(), "debt.interest");
        }
        return updated;
    }

    static void tick(MinecraftServer server, long now) {
        for (var player : List.copyOf(debts(server).value.keySet())) accrue(server, player, now);
    }

    static Accounts.Income income(MinecraftServer server) {
        return new Accounts.Income() {
            public long withheld(UUID player, long cents) {
                return player.equals(Accounts.SERVER) || cents <= 0 ? 0 : Math.min(cents, debt(server, player));
            }
            public void repaid(UUID player, long cents) { reduce(server, player, cents); }
        };
    }

    /** Adds an obligation without creating money, for later fines, margin losses and founding fees. */
    public static boolean addDebt(MinecraftServer server, UUID player, long cents, String reason) {
        if (player.equals(Accounts.SERVER) || cents <= 0 || reason == null || reason.isBlank()) return false;
        var old = accrue(server, player, System.currentTimeMillis());
        if (Long.MAX_VALUE - old.amount() < cents) return false;
        var store = debts(server);
        store.value.put(player, new Debts.Debt(old.amount() + cents, old.principal(), old.updated()));
        store.setDirty();
        Ledger.obligation(server, player, cents, "debt.add." + reason);
        return true;
    }

    static long limit(MinecraftServer server, UUID player) {
        double units = RankGate.ranks.number(RankGate.rankOf(server, player), "loan_limit", Config.loanLimit());
        return Math.round(Math.min(1e15, Math.max(0, units)) * 100);
    }

    static boolean borrow(ServerPlayer player, long cents) {
        var server = player.level().getServer();
        if (server == null || cents <= 0 || cents > limit(server, player.getUUID()) || debt(server, player.getUUID()) != 0) return false;
        if (!Ledger.payout(server, player.getUUID(), cents, "bank.loan")) return false;
        var store = debts(server);
        store.value.put(player.getUUID(), new Debts.Debt(cents, cents, System.currentTimeMillis()));
        store.setDirty();
        Ledger.obligation(server, player.getUUID(), cents, "debt.loan");
        return true;
    }

    /** Repays the requested amount, capped at the current debt; failure changes neither balance nor debt. */
    public static boolean repay(MinecraftServer server, UUID player, long cents) {
        cents = Math.min(cents, debt(server, player));
        if (cents <= 0 || !Ledger.collect(server, player, cents, "bank.repay")) return false;
        reduce(server, player, cents);
        return true;
    }

    private static void reduce(MinecraftServer server, UUID player, long cents) {
        var store = debts(server);
        var updated = Debts.repay(store.value.get(player), cents);
        if (updated.amount() == 0) store.value.remove(player);
        else store.value.put(player, updated);
        store.setDirty();
    }

    static Debts.Debt merge(MinecraftServer server, UUID from, UUID to) {
        if (!debts(server).value.containsKey(from)) return null;
        long now = System.currentTimeMillis();
        var old = accrue(server, from, now); var target = accrue(server, to, now);
        // shortcut: merged loans share the earlier daily clock, retain separate clocks if multiple loans are introduced
        return new Debts.Debt(Math.addExact(old.amount(), target.amount()), Math.addExact(old.principal(), target.principal()), Math.min(old.updated(), target.updated()));
    }

    static void move(MinecraftServer server, UUID from, UUID to, Debts.Debt merged) {
        if (merged == null) return;
        var store = debts(server);
        store.value.remove(from); store.value.put(to, merged); store.setDirty();
    }

    static boolean request(ServerPlayer sender, Commands.Target target, long cents) {
        var server = sender.level().getServer();
        if (server == null || cents <= 0 || target.id().equals(Accounts.SERVER)
                || !requests(server).put(sender.getUUID(), target.id(), cents, Config.paymentRequestMillis(), System.currentTimeMillis())) return false;
        var receiver = server.getPlayerList().getPlayer(target.id());
        if (receiver != null) receiver.sendSystemMessage(Component.translatable("pluginless.bank.requested", sender.getScoreboardName(), Config.format(cents)));
        return true;
    }

    static boolean respond(ServerPlayer player, boolean accept) {
        var server = player.level().getServer();
        if (server == null) return false;
        var request = requests(server).take(player.getUUID(), System.currentTimeMillis());
        if (request == null) return false;
        boolean ok = !accept || Ledger.of(server, player.getUUID().toString(), "bank.request")
                .transfer(player.getUUID(), request.sender(), request.value());
        var sender = server.getPlayerList().getPlayer(request.sender());
        if (sender != null) sender.sendSystemMessage(Component.translatable(ok && accept ? "pluginless.bank.accepted" : "pluginless.bank.declined", player.getScoreboardName()));
        return ok;
    }

    static Component pending(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        var request = requests(server).get(player.getUUID(), System.currentTimeMillis());
        return request == null ? Component.translatable("pluginless.bank.no_request")
                : Component.translatable("pluginless.bank.pending", Commands.name(server, request.sender()), Config.format(request.value()),
                (request.expires() - System.currentTimeMillis() + 999) / 1000);
    }

    static Component history(MinecraftServer server, UUID player) {
        var history = Ledger.history(server, player);
        var result = Component.translatable("pluginless.bank.history");
        for (int i = history.size() - 1; i >= Math.max(0, history.size() - 10); i--) {
            var t = history.get(i);
            if (t.reason().equals("debt.loan") || t.reason().startsWith("debt.add.") || t.reason().equals("debt.interest")) {
                result.append(Component.translatable("pluginless.bank.obligation", java.time.Instant.ofEpochMilli(t.time()).toString(), Config.format(t.amount()), t.reason()));
            } else result.append(Component.translatable("pluginless.bank.transaction", java.time.Instant.ofEpochMilli(t.time()).toString(),
                    endpoint(server, t.from()), endpoint(server, t.to()), Config.format(t.amount()), t.reason()));
        }
        return result;
    }

    static String endpoint(MinecraftServer server, String id) {
        try { return Commands.name(server, UUID.fromString(id)); }
        catch (IllegalArgumentException e) { return Component.translatable("pluginless.bank.endpoint." + id).getString(); }
    }

    static void clear(MinecraftServer server) { REQUESTS.remove(server); }
}
