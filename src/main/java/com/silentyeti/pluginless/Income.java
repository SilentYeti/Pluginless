package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Ways money enters (paychecks, daily reward, mob kills, selling to the server) and the tax summary. */
final class Income {
    private static final Codec<Taxes.Streak> STREAK = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("day").forGetter(Taxes.Streak::day),
            Codec.INT.fieldOf("count").forGetter(Taxes.Streak::count)
    ).apply(instance, Taxes.Streak::new));
    // shortcut: paycheck progress and the kill cap are in memory and AFK time counts, persist them with AFK tracking
    private static final Map<UUID, Long> PLAYED = new HashMap<>();
    private static Taxes.HourlyCap kills = new Taxes.HourlyCap();

    private static Store<Map<UUID, Taxes.Streak>> logins(MinecraftServer server) {
        return Store.open(server, "logins", Codec.unboundedMap(UUIDUtil.STRING_CODEC, STREAK).xmap(HashMap::new, map -> map), HashMap::new);
    }

    static double perk(MinecraftServer server, UUID player, String name) {
        return Math.max(0, RankGate.ranks.number(RankGate.rankOf(server, player), name, 1));
    }

    static void online(ServerPlayer player, long seconds) {
        long interval = Config.paycheckSeconds();
        if (interval == 0) return;
        long total = PLAYED.merge(player.getUUID(), seconds, Long::sum);
        if (total < interval) return;
        // One paycheck however long the gap was: a stalled server must not pay a backlog.
        PLAYED.put(player.getUUID(), total % interval);
        pay(player, Config.paycheck(), "income.paycheck");
    }

    static void login(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null || Config.dailyReward() <= 0) return;
        var store = logins(server);
        var next = store.value.getOrDefault(player.getUUID(), new Taxes.Streak(0, 0)).next(System.currentTimeMillis() / 86_400_000);
        if (next == null) return;
        store.value.put(player.getUUID(), next);
        store.setDirty();
        int extraDays = Math.min(next.count(), Config.dailyStreakMax()) - 1;
        pay(player, Config.dailyReward() + extraDays * Config.dailyStreakBonus(), "income.daily");
    }

    static void kill(ServerPlayer player, String mob) {
        var server = player.level().getServer();
        double reward = Features.settings.mobRewards.getOrDefault(mob, 0.0);
        if (server == null || reward <= 0 || !kills.allow(player.getUUID(), System.currentTimeMillis(), Config.mobRewardCap())) return;
        long cents = Math.round(reward * 100 * perk(server, player.getUUID(), "income_multiplier"));
        if (cents > 0) Ledger.payout(server, player.getUUID(), cents, "income.mob");
    }

    private static void pay(ServerPlayer player, double units, String reason) {
        var server = player.level().getServer();
        long cents = Math.round(units * 100 * perk(server, player.getUUID(), "income_multiplier"));
        if (cents > 0 && Ledger.payout(server, player.getUUID(), cents, reason))
            player.sendSystemMessage(Component.translatable("pluginless." + reason, Config.format(cents)));
    }

    /** What the server pays for the whole stack; 0 when it does not buy the item. */
    static long worth(ItemStack stack) {
        return stack.isEmpty() ? 0 : Math.round(Features.settings.sellPrices.getOrDefault(Stacks.id(stack), 0.0) * 100) * stack.getCount();
    }

    static boolean sell(ServerPlayer player) {
        var server = player.level().getServer();
        var stack = player.getMainHandItem();
        long cents = worth(stack);
        if (server == null || cents <= 0 || !Ledger.payout(server, player.getUUID(), cents, "income.sell")) return false;
        stack.setCount(0);
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    /** Everything the player has paid to the server, summed by reason. */
    static Component taxes(MinecraftServer server, UUID player) {
        var totals = new TreeMap<String, Long>();
        for (var t : Ledger.history(server, player)) {
            if (t.from().equals(player.toString()) && t.to().equals("server")) totals.merge(t.reason().replaceAll(":.*", ""), t.amount(), Long::sum);
        }
        var result = Component.translatable("pluginless.taxes.title", Config.transferTax(), Config.auctionTax(), perk(server, player, "fee_multiplier"));
        totals.forEach((reason, cents) -> result.append(Component.translatable("pluginless.taxes.line", reason, Config.format(cents))));
        return result;
    }

    static void clear() { PLAYED.clear(); kills = new Taxes.HourlyCap(); }
}
