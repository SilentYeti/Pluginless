package com.silentyeti.pluginless;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * What the tax and treasury code needs from a claim mod: who the groups are, who is in them and how many
 * chunks each holds. One adapter per claim mod; {@link ClaimCompat} picks the one that is installed.
 */
interface ClaimGroups {
    /** A party, faction or similar. {@code id} is stable for the group's lifetime, {@code chunks} is its total claimed chunks. */
    record Group(String id, String name, UUID owner, Set<UUID> members, int chunks) {
        public Group {
            members = Set.copyOf(members);
        }
    }

    /** The claim mod this adapter reads, for logs. */
    String mod();

    Collection<Group> groups();

    default Group groupOf(UUID player) {
        for (var group : groups()) if (group.members().contains(player)) return group;
        return null;
    }
}
