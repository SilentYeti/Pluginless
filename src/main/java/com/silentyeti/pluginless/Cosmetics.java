package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Cosmetics {
    record Appearance(String nick, String color, String title) {
        static final Codec<Appearance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("nick").forGetter(Appearance::nick),
                Codec.STRING.fieldOf("color").forGetter(Appearance::color),
                Codec.STRING.fieldOf("title").forGetter(Appearance::title)
        ).apply(instance, Appearance::new));
    }
    private static Store<Map<UUID, Appearance>> appearances(MinecraftServer server) {
        return Store.open(server, "cosmetics", Codec.unboundedMap(UUIDUtil.STRING_CODEC, Appearance.CODEC).xmap(HashMap::new, map -> map), HashMap::new);
    }
    static Appearance of(MinecraftServer server, UUID player) { return appearances(server).value.getOrDefault(player, new Appearance("", "", "")); }
    /** Operators may test valid cosmetics; other players need a rank perk or individual grant. */
    static boolean allowed(ServerPlayer player, String perk) {
        var server = player.level().getServer();
        return server != null && ((RankGate.isOperator(player) && valid(perk))
                || RankGate.ranks.flag(RankGate.rankOf(server, player.getUUID()), perk)
                || grants(server).value.getOrDefault(player.getUUID(), List.of()).contains(perk));
    }
    private static Store<Map<UUID, List<String>>> grants(MinecraftServer server) {
        return Store.open(server, "cosmetic_grants", Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING.listOf()).xmap(HashMap::new, map -> map), HashMap::new);
    }
    static boolean valid(String cosmetic) {
        return List.of("nick", "namecolor", "namegradient", "skull", "hat").contains(cosmetic)
                || cosmetic.startsWith("title.") && Features.settings.titles.containsKey(cosmetic.substring(6));
    }
    static boolean grant(MinecraftServer server, UUID player, String cosmetic, boolean on) {
        if (!valid(cosmetic)) return false;
        var store = grants(server);
        var list = new java.util.ArrayList<>(store.value.getOrDefault(player, List.of()));
        if (on ? list.contains(cosmetic) || !list.add(cosmetic) : !list.remove(cosmetic)) return false;
        store.value.put(player, list); store.setDirty();
        var online = server.getPlayerList().getPlayer(player);
        if (online != null) RankGate.refresh(online);
        return true;
    }
    static void move(MinecraftServer server, UUID from, UUID to) {
        var store = grants(server);
        var moved = store.value.remove(from);
        if (moved == null) return;
        var merged = new java.util.ArrayList<>(store.value.getOrDefault(to, List.of()));
        moved.forEach(cosmetic -> { if (!merged.contains(cosmetic)) merged.add(cosmetic); });
        store.value.put(to, merged); store.setDirty();
    }
    private static void save(ServerPlayer player, Appearance appearance) {
        var store = appearances(player.level().getServer()); store.value.put(player.getUUID(), appearance); store.setDirty(); RankGate.refresh(player);
    }

    static boolean nick(ServerPlayer player, String name) {
        var server = player.level().getServer();
        if (server == null) return false;
        var old = of(server, player.getUUID());
        if (name.equalsIgnoreCase("off")) { save(player, new Appearance("", old.color(), old.title())); return true; }
        var others = new java.util.ArrayList<String>();
        Players.known(server).forEach((real, id) -> { if (!id.equals(player.getUUID())) others.add(real); });
        server.getPlayerList().getPlayers().forEach(online -> { if (!online.getUUID().equals(player.getUUID())) others.add(online.getScoreboardName()); });
        if (!allowed(player, "nick") || !CosmeticNames.valid(name, Config.nickMaxLength(), Features.settings.blockedWords, others)
                || appearances(server).value.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(player.getUUID()) && entry.getValue().nick().equalsIgnoreCase(name))) return false;
        save(player, new Appearance(name, old.color(), old.title())); return true;
    }

    static boolean color(ServerPlayer player, String color) {
        var server = player.level().getServer();
        if (server == null) return false;
        var old = of(server, player.getUUID());
        if (color.equals("off")) { save(player, new Appearance(old.nick(), "", old.title())); return true; }
        if (!allowed(player, color.startsWith("<gradient:") ? "namegradient" : "namecolor")) return false;
        save(player, new Appearance(old.nick(), color, old.title())); return true;
    }

    static boolean title(ServerPlayer player, String title) {
        var server = player.level().getServer();
        if (server == null || !title.equals("off") && (!Features.settings.titles.containsKey(title) || !allowed(player, "title." + title))) return false;
        var old = of(server, player.getUUID());
        save(player, new Appearance(old.nick(), old.color(), title.equals("off") ? "" : title)); return true;
    }

    static Component name(ServerPlayer player, String fallbackColor) {
        return name(player, fallbackColor, player.getName());
    }

    static Component name(ServerPlayer player, String fallbackColor, Component original) {
        var appearance = of(player.level().getServer(), player.getUUID());
        boolean hasNick = allowed(player, "nick") && CosmeticNames.valid(appearance.nick(), Config.nickMaxLength(), Features.settings.blockedWords, List.of());
        String color = appearance.color();
        if (color.isEmpty() || !allowed(player, color.startsWith("<gradient:") ? "namegradient" : "namecolor")) color = fallbackColor;
        if (!hasNick && color.isEmpty()) return original.copy();
        return Text.format(Formatting.colorize(color, hasNick ? appearance.nick() : original.getString()));
    }

    static Component title(ServerPlayer player) {
        var appearance = of(player.level().getServer(), player.getUUID());
        String configured = Features.settings.titles.get(appearance.title());
        return configured != null && allowed(player, "title." + appearance.title()) ? Text.format(" " + configured) : Component.empty();
    }

    static List<String> realnames(MinecraftServer server, String nick) {
        return appearances(server).value.entrySet().stream().filter(entry -> entry.getValue().nick().equalsIgnoreCase(nick))
                .map(entry -> Commands.name(server, entry.getKey())).sorted().toList();
    }

    static void joined(ServerPlayer player) {
        var store = appearances(player.level().getServer());
        for (var entry : List.copyOf(store.value.entrySet())) {
            var appearance = entry.getValue();
            if (!entry.getKey().equals(player.getUUID()) && appearance.nick().equalsIgnoreCase(player.getScoreboardName())) {
                store.value.put(entry.getKey(), new Appearance("", appearance.color(), appearance.title())); store.setDirty();
                var online = player.level().getServer().getPlayerList().getPlayer(entry.getKey());
                if (online != null) RankGate.refresh(online);
            }
        }
    }

    static boolean skull(ServerPlayer player, Commands.Target target) {
        var server = player.level().getServer();
        if (server == null || target.id().equals(Accounts.SERVER) || !allowed(player, "skull")) return false;
        // shortcut: heads need an empty slot, add preflighted stacking if full inventories should merge matching heads
        int slot = -1;
        for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) { slot = i; break; }
        if (slot < 0) return false;
        var head = new ItemStack(Items.PLAYER_HEAD);
        //? if >=26.1 {
        /*head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(target.name()));
        *///?} else {
        head.set(DataComponents.PROFILE, new ResolvableProfile(java.util.Optional.of(target.name()), java.util.Optional.empty(), new com.mojang.authlib.properties.PropertyMap()));
        //?}
        long cost = Config.skullPriceCents();
        if (cost > 0 && !Ledger.collect(server, player.getUUID(), cost, "cosmetic.skull")) return false;
        player.getInventory().setItem(slot, head); player.inventoryMenu.broadcastChanges(); return true;
    }

    static boolean hat(ServerPlayer player) {
        return hat(player, player.getMainHandItem());
    }

    static boolean hat(ServerPlayer player, int slot) {
        return slot >= 0 && slot < 36 && hat(player, player.getInventory().getItem(slot));
    }

    private static boolean hat(ServerPlayer player, ItemStack item) {
        if (!allowed(player, "hat") || !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() || item.isEmpty()) return false;
        // shortcut: requires an empty helmet slot, add curse-safe swapping if players need to replace a worn hat
        player.setItemSlot(EquipmentSlot.HEAD, item.split(1));
        player.inventoryMenu.broadcastChanges(); return true;
    }
}
