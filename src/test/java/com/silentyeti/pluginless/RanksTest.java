package com.silentyeti.pluginless;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
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
}
