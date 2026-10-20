package com.silentyeti.pluginless;

import java.lang.reflect.Method;
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
        var parties = (Stream<?>) call(call(api, "getPartyManager"), "getAllStream");
        var claims = call(api, "getServerClaimsManager");
        var result = new ArrayList<Group>();
        for (var party : parties.toList()) {
            var members = new LinkedHashSet<UUID>();
            for (var member : ((Stream<?>) call(party, "getMemberInfoStream")).toList()) members.add((UUID) call(member, "getUUID"));
            int chunks = 0;
            for (var member : members) {
                var info = call(claims, "getPlayerInfo", member);
                if (info != null) chunks += (Integer) call(info, "getClaimCount");
            }
            result.add(new Group(call(party, "getId").toString(), (String) call(party, "getDefaultName"),
                    (UUID) call(call(party, "getOwner"), "getUUID"), members, chunks));
        }
        return result;
    }

    /**
     * Calls a public method by name. Looks it up on a public class or interface of the target, because the
     * runtime class of an API object is usually an internal one that reflection may not enter.
     */
    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        var method = find(target.getClass(), name, args.length);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        return method.invoke(target, args);
    }

    private static Method find(Class<?> type, String name, int arguments) {
        if (type == null) return null;
        if (java.lang.reflect.Modifier.isPublic(type.getModifiers()))
            for (var method : type.getMethods())
                if (method.getName().equals(name) && method.getParameterCount() == arguments && method.getDeclaringClass().isAssignableFrom(type)
                        && java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
        for (var parent : type.getInterfaces()) {
            var found = find(parent, name, arguments);
            if (found != null) return found;
        }
        return find(type.getSuperclass(), name, arguments);
    }
}
