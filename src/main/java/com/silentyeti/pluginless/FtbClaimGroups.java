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
 * FTB Chunks with FTB Teams, read through reflection. A party (or server) team is a group; chunks come from the
 * claims manager grouped by team, which is a plain read (asking for a team's data would create it). Players without a
 * party sit in a personal team and are not groups, as with Open Parties and Claims. If the API is not what we expect the
 * adapter logs once and reports no groups.
 */
final class FtbClaimGroups implements ClaimGroups {
    static final String CHUNKS_MOD_ID = "ftbchunks";
    static final String TEAMS_MOD_ID = "ftbteams";
    static final String CHUNKS_API = "dev.ftb.mods.ftbchunks.api.FTBChunksAPI";
    static final String TEAMS_API = "dev.ftb.mods.ftbteams.api.FTBTeamsAPI";

    private final Supplier<Object> teams;
    private final Supplier<Object> chunks;
    private boolean broken;

    /** @param teams yields {@code FTBTeamsAPI.api()}; @param chunks yields {@code FTBChunksAPI.api()} */
    FtbClaimGroups(Supplier<Object> teams, Supplier<Object> chunks) {
        this.teams = teams;
        this.chunks = chunks;
    }

    static FtbClaimGroups installed() {
        return new FtbClaimGroups(() -> api(TEAMS_API), () -> api(CHUNKS_API));
    }

    private static Object api(String className) {
        try {
            return Reflect.callStatic(className, "api");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String mod() {
        return "FTB Chunks";
    }

    @Override
    public Collection<Group> groups() {
        if (broken) return List.of();
        try {
            return read(teams.get(), chunks.get());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            Pluginless.LOGGER.error("FTB Chunks is installed but its API could not be read, claim groups are disabled: {}", e.toString());
            return List.of();
        }
    }

    /** Reads party and server teams from the {@code FTBTeamsAPI.API} and {@code FTBChunksAPI.API} instances. */
    @SuppressWarnings("unchecked")
    static List<Group> read(Object teamsApi, Object chunksApi) throws ReflectiveOperationException {
        if (!(Boolean) Reflect.call(teamsApi, "isManagerLoaded") || !(Boolean) Reflect.call(chunksApi, "isManagerLoaded")) return List.of();
        var byTeam = (Map<UUID, Collection<?>>) Reflect.call(Reflect.call(chunksApi, "getManager"), "getClaimedChunksByTeam", (Predicate<Object>) chunk -> true);
        var result = new ArrayList<Group>();
        for (var team : (Collection<?>) Reflect.call(Reflect.call(teamsApi, "getManager"), "getTeams")) {
            if (!(Boolean) Reflect.call(team, "isPartyTeam") && !(Boolean) Reflect.call(team, "isServerTeam")) continue;
            var id = (UUID) Reflect.call(team, "getId");
            var claimed = byTeam.get(id);
            result.add(new Group(id.toString(), (String) Reflect.call(team, "getShortName"), (UUID) Reflect.call(team, "getOwner"),
                    new LinkedHashSet<>((Collection<UUID>) Reflect.call(team, "getMembers")), claimed == null ? 0 : claimed.size()));
        }
        return result;
    }
}
