package com.silentyeti.pluginless;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CooldownsTest {
    @Test
    void playersCommandsExpiryAndRankChangesAreIndependent() {
        var cooldowns = new Cooldowns();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        assertTrue(cooldowns.take(a, "tpa", 30_000, 1000));
        assertFalse(cooldowns.take(a, "tpa", 30_000, 2000));
        assertEquals(29_000, cooldowns.remaining(a, "tpa", 30_000, 2000));
        assertEquals(0, cooldowns.remaining(a, "tpa", 500, 2000));
        assertTrue(cooldowns.take(a, "tpa", 0, 2000));
        assertTrue(cooldowns.take(b, "tpa", 30_000, 2000));
        assertTrue(cooldowns.take(a, "skull", 30_000, 2000));
        assertTrue(cooldowns.take(a, "tpa", 30_000, 31_000));
    }
}
