package com.silentyeti.pluginless;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticNamesTest {
    @Test
    void namesRejectMarkupBlockedWordsLengthAndImpersonationIgnoringCase() {
        assertTrue(CosmeticNames.valid("Yeti_42", 24, List.of("admin"), List.of("Alice")));
        for (var bad : new String[]{"", "&aAlice", "Alice", "ALICE", "anAdmin", "two names", "§rBob", "a".repeat(25)}) {
            assertFalse(CosmeticNames.valid(bad, 24, List.of("admin"), List.of("Alice")), bad);
        }
    }

    @Test
    void colorsAndGradientsUseTheSharedFormatterWithoutAllowingInjectedMarkup() {
        assertEquals("#abcdef", CosmeticNames.color("#ABCDEF"));
        assertEquals("<gradient:#ff5555:#0000ff>", CosmeticNames.gradient("&c", "#0000ff"));
        for (var bad : new String[]{"red", "&k", "#123", "#ff0000&l", "#123456 text", "<gradient:#000000:#ffffff>"}) {
            assertThrows(IllegalArgumentException.class, () -> CosmeticNames.color(bad));
        }
    }
}
