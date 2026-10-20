package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent playtime, reward claims and kit cooldowns, driven by the shared scheduler. */
final class RankRewards {
    record Progress(long seconds, Map<String, Long> rewards, Map<String, Long> kits) {
        static final Codec<Map<String, Long>> TIMES = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(HashMap::new, map -> map);
        static final Codec<Progress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("seconds").forGetter(Progress::seconds),
                TIMES.fieldOf("rewards").forGetter(Progress::rewards),
                TIMES.fieldOf("kits").forGetter(Progress::kits)
        ).apply(instance, Progress::new));
    }

    private static Store<Map<UUID, Progress>> progress(MinecraftServer server) {
        return Store.open(server, "rank_progress", Codec.unboundedMap(UUIDUtil.STRING_CODEC, Progress.CODEC)
                .xmap(HashMap::new, map -> map), HashMap::new);
    }

    static Progress of(MinecraftServer server, UUID player) {
        return progress(server).value.computeIfAbsent(player, id -> new Progress(0, new HashMap<>(), new HashMap<>()));
    }

    static void move(MinecraftServer server, UUID from, UUID to) {
        var store = progress(server);
        var old = store.value.remove(from);
        if (old == null) return;
        var current = store.value.get(to);
        if (current != null) {
            var rewards = old.rewards();
            var kits = old.kits();
            current.rewards().forEach((key, value) -> rewards.merge(key, value, Math::max));
            current.kits().forEach((key, value) -> kits.merge(key, value, Math::max));
            old = new Progress(Math.max(old.seconds(), current.seconds()), old.rewards(), old.kits());
        }
        store.value.put(to, old);
        store.setDirty();
    }

    static String configProblem(Ranks ranks) {
        for (var entry : ranks.kits.entrySet()) {
            var problem = itemsProblem(entry.getValue());
            if (problem != null) return "kit " + entry.getKey() + ": " + problem;
        }
        for (var entry : ranks.ranks.entrySet()) {
            for (var reward : entry.getValue().rewards) {
                var problem = itemsProblem(reward);
                if (problem != null) return "reward " + reward.id + ": " + problem;
            }
        }
        return null;
    }

    private static String itemsProblem(Ranks.Grant grant) {
        for (var id : grant.items.keySet()) {
            var key = ResourceLocation.tryParse(id);
            if (key == null || !BuiltInRegistries.ITEM.containsKey(key) || id.equals("minecraft:air")) return "unknown item " + id;
        }
        return null;
    }

    static boolean canKit(MinecraftServer server, ServerPlayer player, String name) {
        String rank = RankGate.rankOf(server, player.getUUID());
        var verdict = RankGate.ranks.verdict(rank, "kit " + name);
        return RankGate.isOperator(player) || !Boolean.FALSE.equals(verdict)
                && (Boolean.TRUE.equals(verdict) || RankGate.ranks.flag(rank, "kit." + name));
    }

    static boolean kit(ServerPlayer player, String name, long now) {
        var server = player.level().getServer();
        if (server == null) return false;
        var kit = RankGate.ranks.kits.get(name);
        if (kit == null || !canKit(server, player, name)) {
            player.sendSystemMessage(Component.translatable("pluginless.kit.unavailable", name));
            return false;
        }
        var state = of(server, player.getUUID());
        String rank = RankGate.rankOf(server, player.getUUID());
        long millis = Math.round(Math.max(0, RankGate.ranks.number(rank, "cooldown.kit." + name, kit.cooldown)) * 1000);
        var last = state.kits().get(name);
        long remaining = last == null ? 0 : Math.addExact(last, millis) - now;
        if (remaining > 0) {
            player.sendSystemMessage(Component.translatable("pluginless.cooldown", (remaining + 999) / 1000));
            return false;
        }
        if (!grant(player, kit, "kit." + name)) {
            player.sendSystemMessage(Component.translatable("pluginless.reward.failed"));
            return false;
        }
        state.kits().put(name, now);
        progress(server).setDirty();
        player.sendSystemMessage(Component.translatable("pluginless.kit.claimed", name));
        return true;
    }

    static void online(ServerPlayer player, long seconds) {
        var server = player.level().getServer();
        if (server == null || seconds <= 0) return;
        var store = progress(server);
        var old = of(server, player.getUUID());
        // shortcut: no AFK tracking exists yet, exclude AFK seconds when the QOL module provides it
        var state = new Progress(Math.addExact(old.seconds(), seconds), old.rewards(), old.kits());
        store.value.put(player.getUUID(), state);
        store.setDirty();
        reward(player);
        promote(player);
    }

    static void reward(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null) return;
        var state = of(server, player.getUUID());
        String rank = RankGate.rankOf(server, player.getUUID());
        for (var reward : RankGate.ranks.rewards(rank)) {
            long claimed = state.rewards().getOrDefault(reward.id, 0L);
            if (Ranks.rewardCount(reward, state.seconds()) <= claimed) continue;
            if (grant(player, reward, "playtime." + reward.id)) {
                state.rewards().put(reward.id, claimed + 1);
                progress(server).setDirty();
                player.sendSystemMessage(Component.translatable("pluginless.reward.received", reward.id));
            }
        }
    }

    private static void promote(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null) return;
        if (RankGate.temporary(server, player.getUUID())) return;
        var current = RankGate.rankOf(server, player.getUUID());
        for (var entry : RankGate.ranks.tracks.entrySet()) {
            var next = RankGate.ranks.step(entry.getKey(), current, 1);
            var hours = next == null ? null : entry.getValue().autoHours.get(next);
            if (hours != null && of(server, player.getUUID()).seconds() >= Math.round(hours * 3600)) {
                RankGate.setRank(server, player.getUUID(), next);
                player.sendSystemMessage(Component.translatable("pluginless.rank.set", player.getScoreboardName(), next));
                return;
            }
        }
    }

    /** Preflight every inventory slot and the money limit before changing either. Full inventories retry later. */
    private static boolean grant(ServerPlayer player, Ranks.Grant grant, String reason) {
        if (itemsProblem(grant) != null) return false;
        var server = player.level().getServer();
        if (server == null) return false;
        String rank = RankGate.rankOf(server, player.getUUID());
        double amount = grant.money * 100 * Math.max(0, RankGate.ranks.number(rank, "income_multiplier", 1));
        if (!Double.isFinite(amount) || amount >= Long.MAX_VALUE) return false;
        long cents = Math.round(amount);
        var inventory = player.getInventory();
        var slots = new ArrayList<ItemStack>();
        for (int i = 0; i < 36; i++) slots.add(inventory.getItem(i).copy());
        for (var entry : grant.items.entrySet()) {
            var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(entry.getKey())).orElseThrow();
            var stack = new ItemStack(item, 1);
            int remaining = entry.getValue();
            // Fill matching stacks before using empty slots.
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < slots.size() && remaining > 0; i++) {
                    var slot = slots.get(i);
                    if (pass == 0 && !slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                        int add = Math.min(remaining, Math.max(0, inventory.getMaxStackSize(slot) - slot.getCount()));
                        slot.grow(add);
                        remaining -= add;
                    } else if (pass == 1 && slot.isEmpty()) {
                        int add = Math.min(remaining, inventory.getMaxStackSize(stack));
                        slots.set(i, new ItemStack(item, add));
                        remaining -= add;
                    }
                }
            }
            if (remaining > 0) return false;
        }
        if (cents > 0 && !Ledger.of(server, player.getUUID().toString(), reason).deposit(player.getUUID(), cents)) return false;
        for (int i = 0; i < slots.size(); i++) inventory.setItem(i, slots.get(i));
        player.inventoryMenu.broadcastChanges();
        return true;
    }
}
