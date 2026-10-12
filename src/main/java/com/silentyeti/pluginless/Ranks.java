package com.silentyeti.pluginless;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rank configuration and the rule for which commands a rank may use. Plain Java, read straight from
 * config/pluginless/ranks.json, so the rule can be unit-tested without Minecraft.
 *
 * A command is named by its words without arguments: "pay", "eco set", "team modify". A pattern is
 * such a name, a name ending in * ("eco*"), or * for everything.
 */
public final class Ranks {
    public static final class Rank {
        /** Another rank whose rules apply wherever this one says nothing. */
        public String inherits;
        /** Shown before the player's name. & colour codes work. */
        public String prefix = "";
        /** Commands this rank may use even if the game would refuse, such as operator commands. */
        public List<String> allow = List.of();
        /** Commands this rank may not use even if the game would allow them. */
        public List<String> deny = List.of();
    }

    /** The rank of everyone who has not been given one. */
    public String defaultRank = "default";
    public Map<String, Rank> ranks = new LinkedHashMap<>();

    /**
     * TRUE or FALSE if the rank (or one it inherits from) has a rule for this command, null if none does
     * and the game's own permission check should decide. The most specific pattern wins; allow and deny
     * of equal specificity is a deny.
     */
    public Boolean verdict(String rankName, String command) {
        var rank = ranks.get(rankName);
        // the counter stops a rank that inherits from itself, directly or in a loop
        for (int depth = 0; rank != null && depth <= ranks.size(); depth++) {
            int allow = best(rank.allow, command);
            int deny = best(rank.deny, command);
            if (allow >= 0 || deny >= 0) return allow > deny;
            rank = rank.inherits == null ? null : ranks.get(rank.inherits);
        }
        return null;
    }

    public String prefix(String rankName) {
        var rank = ranks.get(rankName);
        return rank == null || rank.prefix == null ? "" : rank.prefix;
    }

    /** What is wrong with this configuration, or null if it is usable. */
    public String problem() {
        if (!ranks.containsKey(defaultRank)) return "defaultRank \"" + defaultRank + "\" is not one of the ranks";
        for (var entry : ranks.entrySet()) {
            var rank = entry.getValue();
            if (rank == null || rank.allow == null || rank.deny == null) return "rank \"" + entry.getKey() + "\" is incomplete";
            if (rank.inherits != null && !ranks.containsKey(rank.inherits)) {
                return "rank \"" + entry.getKey() + "\" inherits from unknown rank \"" + rank.inherits + "\"";
            }
        }
        return null;
    }

    /** How specifically the best matching pattern names the command, or -1 if none matches. */
    private static int best(List<String> patterns, String command) {
        int best = -1;
        for (var pattern : patterns) {
            int score;
            if (pattern.equalsIgnoreCase(command)) score = Integer.MAX_VALUE;
            else if (pattern.endsWith("*") && command.regionMatches(true, 0, pattern, 0, pattern.length() - 1)) score = pattern.length() - 1;
            else continue;
            best = Math.max(best, score);
        }
        return best;
    }
}
