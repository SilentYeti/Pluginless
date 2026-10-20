package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Names, including provisional accounts granted before a player's first login. */
final class Players {
    private static final Codec<Map<String, UUID>> NAMES = Codec.unboundedMap(Codec.STRING, UUIDUtil.STRING_CODEC)
            .xmap(HashMap::new, map -> map);

    private static Store<Map<String, UUID>> names(MinecraftServer server, String key) {
        return Store.open(server, key, NAMES, HashMap::new);
    }

    static UUID resolve(MinecraftServer server, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        var online = server.getPlayerList().getPlayerByName(name);
        if (online != null) { joined(online); return online.getUUID(); }
        var known = names(server, "player_names");
        //? if >=26.1 {
        /*var profile = server.services().nameToIdCache().get(name);
        var id = profile.map(p -> p.id()).orElseGet(() -> known.value.getOrDefault(lower, UUIDUtil.createOfflinePlayerUUID(lower)));
        *///?} else {
        var profile = server.getProfileCache().get(name);
        var id = profile.map(p -> p.getId()).orElseGet(() -> known.value.getOrDefault(lower, UUIDUtil.createOfflinePlayerUUID(lower)));
        //?}
        carry(server, lower, id);
        known.value.put(lower, id);
        known.setDirty();
        // Verified online-mode profiles belong to their UUID, not to whoever later uses the same name.
        if (profile.isEmpty() || !server.usesAuthentication()) {
            var pending = names(server, "pending_players");
            pending.value.put(lower, id);
            pending.setDirty();
        }
        return id;
    }

    static void joined(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null) return;
        String lower = player.getScoreboardName().toLowerCase(Locale.ROOT);
        var id = player.getUUID();
        carry(server, lower, id);
        var known = names(server, "player_names");
        known.value.put(lower, id);
        known.setDirty();
        Ledger.of(server).open(id);
        Cosmetics.joined(player);
        RankGate.expire(server, System.currentTimeMillis());
    }

    private static void carry(MinecraftServer server, String lower, UUID id) {
        var pending = names(server, "pending_players");
        var old = pending.value.get(lower);
        if (old != null && !old.equals(id)) {
            var debt = Bank.merge(server, old, id);
            if (!Ledger.of(server, "server", "identity").move(old, id)) throw new IllegalStateException("account identity merge overflow: " + lower);
            Bank.move(server, old, id, debt);
            RankGate.move(server, old, id);
            Cosmetics.move(server, old, id);
        }
        if (pending.value.remove(lower) != null) pending.setDirty();
    }

    static String name(MinecraftServer server, UUID id) {
        if (id.equals(Accounts.SERVER)) return "@server";
        return names(server, "player_names").value.entrySet().stream().filter(entry -> entry.getValue().equals(id))
                .map(Map.Entry::getKey).findFirst().orElse(id.toString());
    }

    static Map<String, UUID> known(MinecraftServer server) {
        return Map.copyOf(names(server, "player_names").value);
    }
}
