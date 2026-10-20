package com.silentyeti.pluginless;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.Optional;

/** Picks the claim-mod adapter for this server. Without a supported claim mod nothing is active and nothing else changes. */
final class ClaimCompat {
    private static ClaimGroups active;

    private ClaimCompat() {}

    /** Called on server start. */
    static void init(MinecraftServer server) {
        active = null;
        if (ModList.get().isLoaded(OpacClaimGroups.MOD_ID)) {
            active = new OpacClaimGroups(() -> {
                try {
                    return Class.forName(OpacClaimGroups.API).getMethod("get", MinecraftServer.class).invoke(null, server);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            Pluginless.LOGGER.info("Claim compat: using {}", active.mod());
        }
    }

    static Optional<ClaimGroups> active() {
        return Optional.ofNullable(active);
    }

    static void clear() {
        active = null;
    }
}
