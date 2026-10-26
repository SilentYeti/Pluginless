package com.silentyeti.pluginless;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Open Parties and Claims, read through reflection so the mod is not a build dependency: it publishes one
 * artifact per Minecraft version and is optional here. A party is a group; claims belong to players, so a
 * party's chunks are the sum of its members' claim counts. If the API is not what we expect the adapter
 * logs once and reports no groups.
 */
final class OpacClaimGroups implements ClaimGroups {
    static final String MOD_ID = "openpartiesandclaims";
    static final String API = "xaero.pac.common.server.api.OpenPACServerAPI";

    private final Supplier<Object> api;
    private boolean broken;

    /** @param api yields the {@code OpenPACServerAPI} instance of the running server */
    OpacClaimGroups(Supplier<Object> api) {
        this.api = api;
    }

    @Override
    public String mod() {
        return "Open Parties and Claims";
    }

    @Override
    public Collection<Group> groups() {
        if (broken) return List.of();
        try {
            return read(api.get());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            Pluginless.LOGGER.error("Open Parties and Claims is installed but its API could not be read, claim groups are disabled: {}", e.toString());
            return List.of();
        }
    }

    /** Reads parties from an {@code OpenPACServerAPI} instance (anything with the same methods). */
    static List<Group> read(Object api) throws ReflectiveOperationException {
        var parties = (Stream<?>) Reflect.call(Reflect.call(api, "getPartyManager"), "getAllStream");
        var claims = Reflect.call(api, "getServerClaimsManager");
        var result = new ArrayList<Group>();
        for (var party : parties.toList()) {
            var members = new LinkedHashSet<UUID>();
            for (var member : ((Stream<?>) Reflect.call(party, "getMemberInfoStream")).toList()) members.add((UUID) Reflect.call(member, "getUUID"));
            int chunks = 0;
            for (var member : members) {
                var info = Reflect.call(claims, "getPlayerInfo", member);
                if (info != null) chunks += (Integer) Reflect.call(info, "getClaimCount");
            }
            result.add(new Group(Reflect.call(party, "getId").toString(), (String) Reflect.call(party, "getDefaultName"),
                    (UUID) Reflect.call(Reflect.call(party, "getOwner"), "getUUID"), members, chunks));
        }
        return result;
    }
}
