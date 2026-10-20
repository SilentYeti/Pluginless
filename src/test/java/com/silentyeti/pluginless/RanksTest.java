package com.silentyeti.pluginless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RanksTest {
    private final Ranks ranks = new Ranks();

    private Ranks.Rank rank(String name, String inherits, List<String> allow, List<String> deny) {
        var rank = new Ranks.Rank();
        rank.inherits = inherits;
        rank.allow = allow;
        rank.deny = deny;
        ranks.ranks.put(name, rank);
        return rank;
    }

    @Test
    void noRuleLeavesTheDecisionToTheGame() {
        rank("default", null, List.of("pay"), List.of());
        assertNull(ranks.verdict("default", "gamemode"));
        assertNull(ranks.verdict("no such rank", "pay"));
    }

    @Test
    void allowAndDenyMatchExactNamesAndSubcommandsSeparately() {
        rank("teller", null, List.of("eco"), List.of("eco set"));
        assertEquals(true, ranks.verdict("teller", "eco"));
        assertEquals(false, ranks.verdict("teller", "eco set"));
        assertNull(ranks.verdict("teller", "eco add"));
    }

    @Test
    void theMostSpecificPatternWinsAndTiesDeny() {
        rank("whitelist", null, List.of("pay", "bal*"), List.of("*"));
        assertEquals(true, ranks.verdict("whitelist", "pay"));
        assertEquals(true, ranks.verdict("whitelist", "balance"));
        assertEquals(false, ranks.verdict("whitelist", "gamemode"));

        rank("blacklist", null, List.of("*"), List.of("stop", "ban*"));
        assertEquals(true, ranks.verdict("blacklist", "gamemode"));
        assertEquals(false, ranks.verdict("blacklist", "stop"));
        assertEquals(false, ranks.verdict("blacklist", "ban-ip"));

        rank("tie", null, List.of("pay"), List.of("pay"));
        assertEquals(false, ranks.verdict("tie", "pay"));
    }

    @Test
    void aRankFallsBackToTheOneItInheritsFromAndCanOverrideIt() {
        rank("default", null, List.of(), List.of("baltop"));
        rank("vip", "default", List.of("baltop", "fly"), List.of());
        rank("mod", "vip", List.of("kick"), List.of());
        assertEquals(false, ranks.verdict("default", "baltop"));
        assertEquals(true, ranks.verdict("vip", "baltop"));
        assertEquals(true, ranks.verdict("mod", "fly"));
        assertEquals(true, ranks.verdict("mod", "baltop"));
    }

    @Test
    void anInheritanceLoopEndsWithNoRule() {
        rank("a", "b", List.of(), List.of());
        rank("b", "a", List.of(), List.of());
        assertNull(ranks.verdict("a", "pay"));
    }

    @Test
    void problemNamesWhatIsWrongWithAConfiguration() {
        assertNotNull(ranks.problem());
        rank("default", "missing", List.of(), List.of());
        assertNotNull(ranks.problem());
        ranks.ranks.get("default").inherits = null;
        assertNull(ranks.problem());
    }

    @Test
    void perksInheritAndFlagsCanBeExplicitlyDisabled() {
        var base = rank("default", null, List.of(), List.of());
        base.perks = Map.of("homes", 5, "income_multiplier", 1.5, "nick", true);
        var vip = rank("vip", "default", List.of(), List.of());
        vip.perks = Map.of("homes", 10, "nick", false);
        assertEquals(10, ranks.number("vip", "homes", 1));
        assertEquals(1.5, ranks.number("vip", "income_multiplier", 1));
        assertEquals(3, ranks.number("vip", "shops", 3));
        assertFalse(ranks.flag("vip", "nick"));
        assertEquals(1.5, ranks.perks("vip").get("income_multiplier"));
        assertNull(ranks.perk("missing", "homes"));
    }

    @Test
    void cooldownsUseMostSpecificCommandAndInheritedPerks() {
        var base = rank("default", null, List.of(), List.of());
        base.perks = Map.of("cooldown.tpa", 5);
        ranks.cooldowns.put("tpa", 30.0);
        ranks.cooldowns.put("eco", 10.0);
        ranks.cooldowns.put("eco add", 2.0);
        assertEquals("eco add", ranks.cooldownKey("eco add"));
        assertEquals("eco", ranks.cooldownKey("eco remove"));
        assertNull(ranks.cooldownKey("ecology"));
        assertEquals(5000, ranks.cooldownMillis("default", "tpa"));
    }

    @Test
    void tracksStopAtEndsAndRewardsHaveStableInheritedIds() {
        var base = rank("default", null, List.of(), List.of());
        var vip = rank("vip", "default", List.of(), List.of());
        var track = new Ranks.Track();
        track.ranks = List.of("default", "vip");
        ranks.tracks.put("main", track);
        assertEquals("vip", ranks.step("main", "default", 1));
        assertEquals("default", ranks.step("main", "vip", -1));
        assertNull(ranks.step("main", "vip", 1));
        assertNull(ranks.step("main", "default", -1));
        assertNull(ranks.step("main", "missing", 1));
        var reward = new Ranks.Reward();
        reward.id = "hour";
        reward.hours = 1;
        base.rewards = List.of(reward);
        assertEquals(List.of(reward), ranks.rewards("vip"));
        assertEquals(0, Ranks.rewardCount(reward, 3599));
        assertEquals(1, Ranks.rewardCount(reward, 7200));
        reward.repeat = true;
        assertEquals(2, Ranks.rewardCount(reward, 7200));
        vip.chatFormat = "[{name}] {message}";
        assertEquals(vip.chatFormat, ranks.chatFormat("vip"));
    }

    @Test
    void invalidMapsPerksLoopsAndRewardsCannotBeApplied() {
        var base = rank("default", null, List.of(), List.of());
        base.perks = Map.of("homes", "five");
        assertNotNull(ranks.problem());
        base.perks = Map.of("cooldown.tpa", -1);
        assertNotNull(ranks.problem());
        base.perks = Map.of("homes", 5);
        assertNull(ranks.problem());
        base.inherits = "default";
        assertNotNull(ranks.problem());
        base.inherits = null;
        var kit = new Ranks.Kit();
        kit.money = Double.NaN;
        ranks.kits.put("starter", kit);
        assertNotNull(ranks.problem());
        kit.money = 0;
        kit.items = Map.of("minecraft:bread", -1);
        assertNotNull(ranks.problem());
        kit.items = Map.of("minecraft:bread", 2);
        assertNull(ranks.problem());
        base.allow = java.util.Arrays.asList((String) null);
        assertNotNull(ranks.problem());
    }
}
