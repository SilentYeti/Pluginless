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
}
