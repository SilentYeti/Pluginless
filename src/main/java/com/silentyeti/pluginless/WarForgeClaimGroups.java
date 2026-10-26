package com.silentyeti.pluginless;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * WarForge factions, read through reflection from its faction storage. WarForge's public API answers "who owns this
 * chunk" but does not list factions or members, so this reads the storage's {@code getAllFactions()} and the plain
 * public fields of {@code Faction}. A faction's chunks are its claim count. The safe zone and war zone pseudo-factions are
 * skipped. If the layout is not what we expect the adapter logs once and reports no groups.
 */
final class WarForgeClaimGroups implements ClaimGroups {
    static final String MOD_ID = "warforge";
    static final String MOD_CLASS = "com.flansmod.warforge.common.WarForgeMod";
    static final String STORAGE_CLASS = "com.flansmod.warforge.server.FactionStorage";

    private final Supplier<Object> storage;
    private final Predicate<UUID> neutral;
    private boolean broken;

    /** @param storage yields the {@code FactionStorage}; @param neutral true for the safe-zone and war-zone ids */
    WarForgeClaimGroups(Supplier<Object> storage, Predicate<UUID> neutral) {
        this.storage = storage;
        this.neutral = neutral;
    }

    static WarForgeClaimGroups installed() {
        return new WarForgeClaimGroups(() -> {
            try {
                return Class.forName(MOD_CLASS).getField("FACTIONS").get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }, id -> {
            try {
                return (Boolean) Reflect.callStatic(STORAGE_CLASS, "IsNeutralZone", id);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public String mod() {
        return "WarForge";
    }

    @Override
    public Collection<Group> groups() {
        if (broken) return List.of();
        try {
            return read(storage.get(), neutral);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            Pluginless.LOGGER.error("WarForge is installed but its factions could not be read, claim groups are disabled: {}", e.toString());
            return List.of();
        }
    }

    /** Reads factions from a {@code FactionStorage} (anything with {@code getAllFactions()} and the same public fields). */
    static List<Group> read(Object storage, Predicate<UUID> neutral) throws ReflectiveOperationException {
        var result = new ArrayList<Group>();
        for (var faction : (Collection<?>) Reflect.call(storage, "getAllFactions")) {
            var id = (UUID) Reflect.field(faction, "uuid");
            if (id == null || id.equals(new UUID(0, 0)) || neutral.test(id)) continue;
            var members = new LinkedHashSet<UUID>(((Map<UUID, ?>) Reflect.field(faction, "members")).keySet());
            int chunks = ((Map<?, ?>) Reflect.field(faction, "claims")).size();
            result.add(new Group(id.toString(), (String) Reflect.field(faction, "name"), (UUID) Reflect.call(faction, "getLeaderId"), members, chunks));
        }
        return result;
    }
}
