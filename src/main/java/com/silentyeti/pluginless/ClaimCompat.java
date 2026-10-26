package com.silentyeti.pluginless;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.Optional;
import java.util.function.Predicate;

/** Picks the claim-mod adapter for this server. Without a supported claim mod nothing is active and nothing else changes. */
final class ClaimCompat {
    private static ClaimGroups active;

    private ClaimCompat() {}

    /** Called on server start. */
    static void init(MinecraftServer server) {
        active = null;
        var mod = select(id -> ModList.get().isLoaded(id));
        if (mod == Mod.WARFORGE) active = WarForgeClaimGroups.installed();
        else if (mod == Mod.FTB) active = FtbClaimGroups.installed();
        else if (mod == Mod.OPAC) {
            active = new OpacClaimGroups(() -> {
                try {
                    return Class.forName(OpacClaimGroups.API).getMethod("get", MinecraftServer.class).invoke(null, server);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        if (active != null) Pluginless.LOGGER.info("Claim compat: using {}", active.mod());
    }

    enum Mod { WARFORGE, FTB, OPAC }

    /**
     * The claim mod to read when several are installed: WarForge first (its factions are the real groups on a WarForge
     * server), then FTB Chunks with FTB Teams, then Open Parties and Claims. Null when none is.
     */
    static Mod select(Predicate<String> loaded) {
        if (loaded.test(WarForgeClaimGroups.MOD_ID)) return Mod.WARFORGE;
        if (loaded.test(FtbClaimGroups.CHUNKS_MOD_ID) && loaded.test(FtbClaimGroups.TEAMS_MOD_ID)) return Mod.FTB;
        if (loaded.test(OpacClaimGroups.MOD_ID)) return Mod.OPAC;
        return null;
    }

    static Optional<ClaimGroups> active() {
        return Optional.ofNullable(active);
    }

    static void clear() {
        active = null;
    }
}
