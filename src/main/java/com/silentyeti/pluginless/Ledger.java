package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Ledger {
    private static final Codec<Map<UUID, Long>> BALANCES =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG).xmap(HashMap::new, map -> map);

    public static Accounts of(MinecraftServer server) {
        var store = Store.open(server, "ledger", BALANCES, HashMap::new);
        return new Accounts(store.value, Config.startBalanceCents(), store::setDirty);
    }
}
