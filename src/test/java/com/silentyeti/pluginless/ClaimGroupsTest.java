package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ClaimGroupsTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), LONER = UUID.randomUUID();

    /** The interface works with no claim mod at all. */
    private static final ClaimGroups FAKE = new ClaimGroups() {
        public String mod() { return "fake"; }
        public Collection<Group> groups() {
            return List.of(new Group("1", "Reds", A, Set.of(A, B), 7), new Group("2", "Blues", C, Set.of(C), 0));
        }
    };

    @Test
    void groupOfFindsTheGroupAPlayerIsIn() {
        assertEquals("Reds", FAKE.groupOf(B).name());
        assertEquals(7, FAKE.groupOf(A).chunks());
        assertNull(FAKE.groupOf(LONER));
    }

    @Test
    void groupMembersCannotBeChangedAfterwards() {
        var members = new java.util.HashSet<>(Set.of(A));
        var group = new ClaimGroups.Group("1", "x", A, members, 1);
        members.add(B);
        assertEquals(Set.of(A), group.members());
    }

    // Same method names and shapes as Open Parties and Claims, behind public interfaces like the real API.
    public interface Api { Object getPartyManager(); Object getServerClaimsManager(); }
    public interface PartyManager { Stream<Object> getAllStream(); }
    public interface Party { Stream<Object> getMemberInfoStream(); UUID getId(); String getDefaultName(); Object getOwner(); }
    public interface Member { UUID getUUID(); }
    public interface Claims { Object getPlayerInfo(UUID id); }
    public interface Info { int getClaimCount(); }

    private record M(UUID getUUID) implements Member {}
    private record P(UUID getId, String getDefaultName, List<UUID> ids) implements Party {
        public Stream<Object> getMemberInfoStream() { return ids.stream().map(id -> (Object) new M(id)); }
        public Object getOwner() { return new M(ids.get(0)); }
    }

    @Test
    void opacAdapterSumsMemberClaimsAcrossPartyMembers() throws Exception {
        var counts = Map.of(A, 3, B, 4);
        var partyId = UUID.randomUUID();
        // Private classes on purpose: the adapter must call through the public interfaces.
        var api = (Api) new Api() {
            public Object getPartyManager() { return (PartyManager) () -> Stream.of(new P(partyId, "Reds", List.of(A, B)), new P(UUID.randomUUID(), "Solo", List.of(C))); }
            public Object getServerClaimsManager() { return (Claims) id -> counts.containsKey(id) ? (Info) () -> counts.get(id) : null; }
        };
        var groups = OpacClaimGroups.read(api);
        assertEquals(2, groups.size());
        assertEquals(new ClaimGroups.Group(partyId.toString(), "Reds", A, Set.of(A, B), 7), groups.get(0));
        assertEquals(0, groups.get(1).chunks());
    }

    @Test
    void opacAdapterFailsLoudlyOnAnUnexpectedApi() {
        assertThrows(ReflectiveOperationException.class, () -> OpacClaimGroups.read(new Object()));
    }

    // Same method names and shapes as FTB Teams 2101.1.11 and FTB Chunks 2101.1.22 (checked against the published jars).
    public interface TeamsApi { boolean isManagerLoaded(); Object getManager(); }
    public interface TeamManager { Collection<Object> getTeams(); }
    public interface Team { UUID getId(); String getShortName(); UUID getOwner(); Set<UUID> getMembers(); boolean isPartyTeam(); boolean isServerTeam(); }
    public interface ChunksApi { boolean isManagerLoaded(); Object getManager(); }
    public interface ChunkManager { Map<UUID, Collection<Object>> getClaimedChunksByTeam(java.util.function.Predicate<Object> filter); }

    private record T(UUID getId, String getShortName, UUID getOwner, Set<UUID> getMembers, boolean isPartyTeam, boolean isServerTeam) implements Team {}

    private static TeamsApi teams(boolean loaded, Object... teams) {
        return new TeamsApi() {
            public boolean isManagerLoaded() { return loaded; }
            public Object getManager() { return (TeamManager) () -> List.of(teams); }
        };
    }

    private static ChunksApi chunks(Map<UUID, Collection<Object>> byTeam) {
        return new ChunksApi() {
            public boolean isManagerLoaded() { return true; }
            public Object getManager() { return (ChunkManager) filter -> { byTeam.values().forEach(c -> c.forEach(filter::test)); return byTeam; }; }
        };
    }

    @Test
    void ftbAdapterReadsPartiesAndCountsChunksPerTeam() throws Exception {
        var party = UUID.randomUUID();
        var server = UUID.randomUUID();
        var solo = UUID.randomUUID();
        var teams = teams(true,
                new T(party, "Reds", A, Set.of(A, B), true, false),
                new T(server, "Spawn", A, Set.of(), false, true),
                new T(solo, "C", C, Set.of(C), false, false));
        var groups = FtbClaimGroups.read(teams, chunks(Map.of(party, List.of(new Object(), new Object(), new Object()), solo, List.of(new Object()))));
        assertEquals(2, groups.size());
        assertEquals(new ClaimGroups.Group(party.toString(), "Reds", A, Set.of(A, B), 3), groups.get(0));
        assertEquals(0, groups.get(1).chunks());
        assertEquals("Spawn", groups.get(1).name());
    }

    @Test
    void ftbAdapterReportsNothingBeforeItsManagersAreLoaded() throws Exception {
        assertTrue(FtbClaimGroups.read(teams(false), chunks(Map.of())).isEmpty());
    }

    @Test
    void ftbAdapterFailsLoudlyOnAnUnexpectedApi() {
        assertThrows(ReflectiveOperationException.class, () -> FtbClaimGroups.read(new Object(), new Object()));
    }

    // Plain data classes shaped like WarForge's Faction and FactionStorage (public fields, same names).
    public static class Faction {
        public UUID uuid;
        public String name;
        public Map<UUID, Object> members = new java.util.LinkedHashMap<>();
        public Map<Object, Integer> claims = new java.util.HashMap<>();
        UUID leader;
        public UUID getLeaderId() { return leader; }
        Faction(UUID uuid, String name, UUID leader, List<UUID> members, int claims) {
            this.uuid = uuid; this.name = name; this.leader = leader;
            members.forEach(id -> this.members.put(id, new Object()));
            for (int i = 0; i < claims; i++) this.claims.put(i, 0);
        }
    }
    public static class Storage {
        private final List<Faction> all;
        Storage(Faction... all) { this.all = List.of(all); }
        public Collection<Faction> getAllFactions() { return all; }
    }

    @Test
    void warForgeAdapterReadsFactionsAndSkipsTheNeutralZones() throws Exception {
        var red = UUID.randomUUID();
        var safe = UUID.randomUUID();
        var storage = new Storage(new Faction(red, "Reds", A, List.of(A, B), 5), new Faction(safe, "SafeZone", null, List.of(), 40),
                new Faction(new UUID(0, 0), "null", null, List.of(), 1));
        var groups = WarForgeClaimGroups.read(storage, safe::equals);
        assertEquals(List.of(new ClaimGroups.Group(red.toString(), "Reds", A, Set.of(A, B), 5)), groups);
    }

    @Test
    void warForgeAdapterFailsLoudlyOnAnUnexpectedLayout() {
        assertThrows(ReflectiveOperationException.class, () -> WarForgeClaimGroups.read(new Object(), id -> false));
    }

    @Test
    void theClaimModIsChosenWarForgeThenFtbThenOpac() {
        assertNull(ClaimCompat.select(id -> false));
        assertEquals(ClaimCompat.Mod.OPAC, ClaimCompat.select(Set.of("openpartiesandclaims")::contains));
        assertEquals(ClaimCompat.Mod.FTB, ClaimCompat.select(Set.of("ftbchunks", "ftbteams", "openpartiesandclaims")::contains));
        assertNull(ClaimCompat.select(Set.of("ftbchunks")::contains));
        assertEquals(ClaimCompat.Mod.WARFORGE, ClaimCompat.select(Set.of("warforge", "ftbchunks", "ftbteams", "openpartiesandclaims")::contains));
    }
}
