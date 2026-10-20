package com.silentyeti.pluginless;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;

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
        public String nameColor = "";
        public String suffix = "";
        /** Tokens: {prefix}, {name}, {suffix}, {message}. Null retains vanilla chat. */
        public String chatFormat;
        public String messageColor = "";
        public Map<String, Object> perks = new LinkedHashMap<>();
        public List<Reward> rewards = List.of();
        /** Commands this rank may use even if the game would refuse, such as operator commands. */
        public List<String> allow = List.of();
        /** Commands this rank may not use even if the game would allow them. */
        public List<String> deny = List.of();
    }

    /** The rank of everyone who has not been given one. */
    public String defaultRank = "default";
    public Map<String, Rank> ranks = new LinkedHashMap<>();
    /** Seconds, by command words (arguments excluded). Subcommands override their root. */
    public Map<String, Double> cooldowns = new LinkedHashMap<>();
    public Map<String, Kit> kits = new LinkedHashMap<>();
    public Map<String, Track> tracks = new LinkedHashMap<>();

    public static class Grant {
        /** Money in currency units, items by registry ID and count. */
        public double money;
        public Map<String, Integer> items = new LinkedHashMap<>();
    }

    public static final class Reward extends Grant {
        public String id;
        public double hours;
        public boolean repeat;
    }

    public static final class Kit extends Grant {
        public double cooldown = 86400;
    }

    public static final class Track {
        public List<String> ranks = List.of();
        /** Destination rank -> total hours online, or price in currency units. */
        public Map<String, Double> autoHours = new LinkedHashMap<>();
        public Map<String, Double> prices = new LinkedHashMap<>();
    }

    public Object perk(String rankName, String key) {
        var rank = ranks.get(rankName);
        for (int depth = 0; rank != null && depth <= ranks.size(); depth++) {
            if (rank.perks.containsKey(key)) return rank.perks.get(key);
            rank = rank.inherits == null ? null : ranks.get(rank.inherits);
        }
        return null;
    }

    public double number(String rank, String key, double fallback) {
        var value = perk(rank, key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    public boolean flag(String rank, String key) {
        return Boolean.TRUE.equals(perk(rank, key));
    }

    public Map<String, Object> perks(String name) {
        var result = new LinkedHashMap<String, Object>();
        var rank = ranks.get(name);
        for (int depth = 0; rank != null && depth <= ranks.size(); depth++) {
            rank.perks.forEach(result::putIfAbsent);
            rank = rank.inherits == null ? null : ranks.get(rank.inherits);
        }
        return result;
    }

    public List<Reward> rewards(String name) {
        var result = new LinkedHashMap<String, Reward>();
        var rank = ranks.get(name);
        for (int depth = 0; rank != null && depth <= ranks.size(); depth++) {
            for (var reward : rank.rewards) result.putIfAbsent(reward.id, reward);
            rank = rank.inherits == null ? null : ranks.get(rank.inherits);
        }
        return new ArrayList<>(result.values());
    }

    public String chatFormat(String name) {
        var rank = ranks.get(name);
        for (int depth = 0; rank != null && depth <= ranks.size(); depth++) {
            if (rank.chatFormat != null) return rank.chatFormat;
            rank = rank.inherits == null ? null : ranks.get(rank.inherits);
        }
        return null;
    }

    public String cooldownKey(String command) {
        String best = null;
        for (var key : cooldowns.keySet()) {
            if ((command.equals(key) || command.startsWith(key + " ")) && (best == null || key.length() > best.length())) best = key;
        }
        return best;
    }

    public long cooldownMillis(String rank, String key) {
        return Math.round(Math.max(0, number(rank, "cooldown." + key, cooldowns.getOrDefault(key, 0.0))) * 1000);
    }

    public String step(String track, String rank, int direction) {
        var path = tracks.get(track);
        if (path == null) return null;
        int at = path.ranks.indexOf(rank);
        int next = at + direction;
        return at < 0 || next < 0 || next >= path.ranks.size() ? null : path.ranks.get(next);
    }

    public static long rewardCount(Reward reward, long seconds) {
        long interval = Math.max(1, Math.round(reward.hours * 3600));
        long count = seconds / interval;
        return reward.repeat ? count : Math.min(1, count);
    }

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
        if (ranks == null || cooldowns == null || kits == null || tracks == null) return "a configuration map is null";
        if (!ranks.containsKey(defaultRank)) return "defaultRank \"" + defaultRank + "\" is not one of the ranks";
        for (var entry : ranks.entrySet()) {
            var rank = entry.getValue();
            if (!key(entry.getKey()) || rank == null || rank.allow == null || rank.deny == null || rank.perks == null
                    || rank.rewards == null || rank.prefix == null || rank.nameColor == null || rank.suffix == null || rank.messageColor == null) {
                return "rank \"" + entry.getKey() + "\" is incomplete";
            }
            if (rank.allow.stream().anyMatch(p -> p == null || p.isBlank()) || rank.deny.stream().anyMatch(p -> p == null || p.isBlank())) {
                return "rank " + entry.getKey() + " has an empty command rule";
            }
            var seen = new HashSet<String>();
            String parent = entry.getKey();
            while (parent != null) {
                if (!ranks.containsKey(parent)) return "rank " + entry.getKey() + " inherits from unknown rank " + parent;
                if (!seen.add(parent)) return "rank " + entry.getKey() + " has an inheritance loop";
                var ancestor = ranks.get(parent);
                if (ancestor == null) return "rank " + parent + " is null";
                parent = ancestor.inherits;
            }
            for (var perk : rank.perks.entrySet()) {
                if (perk.getKey() == null || perk.getKey().isBlank() || !(perk.getValue() instanceof Boolean
                        || perk.getValue() instanceof Number n && Double.isFinite(n.doubleValue()))) return "rank " + entry.getKey() + " has an invalid perk";
                if (perk.getValue() instanceof Number n && (perk.getKey().startsWith("cooldown.") || perk.getKey().endsWith("_multiplier"))
                        && !seconds(n.doubleValue())) return "rank " + entry.getKey() + " has an invalid duration or multiplier";
            }
            var ids = new HashSet<String>();
            for (var reward : rank.rewards) {
                if (reward == null || !key(reward.id) || !ids.add(reward.id) || !seconds(reward.hours * 3600) || reward.hours <= 0
                        || grantProblem(reward) != null) return "rank " + entry.getKey() + " has an invalid reward";
            }
        }
        for (var cooldown : cooldowns.entrySet()) {
            if (cooldown.getKey() == null || !cooldown.getKey().matches("\\S+(?: \\S+)*")
                    || cooldown.getValue() == null || !seconds(cooldown.getValue())) return "invalid cooldown";
        }
        for (var entry : kits.entrySet()) {
            if (!key(entry.getKey()) || entry.getValue() == null || !seconds(entry.getValue().cooldown)
                    || grantProblem(entry.getValue()) != null) return "invalid kit " + entry.getKey();
        }
        for (var entry : tracks.entrySet()) {
            var track = entry.getValue();
            if (!key(entry.getKey()) || track == null || track.ranks == null || track.ranks.size() < 2 || track.autoHours == null || track.prices == null
                    || new HashSet<>(track.ranks).size() != track.ranks.size() || track.ranks.stream().anyMatch(r -> !ranks.containsKey(r))) return "invalid track " + entry.getKey();
            for (var hours : track.autoHours.entrySet()) {
                if (!track.ranks.contains(hours.getKey()) || hours.getValue() == null || !seconds(hours.getValue() * 3600)) return "invalid autoHours in " + entry.getKey();
            }
            for (var price : track.prices.entrySet()) {
                if (!track.ranks.contains(price.getKey()) || price.getValue() == null || !money(price.getValue())) return "invalid price in " + entry.getKey();
            }
        }
        return null;
    }

    private static boolean key(String key) {
        return key != null && !key.isBlank();
    }

    private static boolean seconds(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1e12;
    }

    private static boolean money(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1e15;
    }

    static String grantProblem(Grant grant) {
        if (!money(grant.money) || grant.items == null) return "invalid money or items";
        for (var item : grant.items.entrySet()) {
            if (item.getKey() == null || !item.getKey().matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || item.getValue() == null
                    || item.getValue() <= 0 || item.getValue() > 2304) return "invalid item or count";
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
