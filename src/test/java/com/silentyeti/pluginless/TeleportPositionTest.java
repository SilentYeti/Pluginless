package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeleportPositionTest {
    @Test
    void warmupIgnoresRotationAndFloatingPointJitterButCancelsOnMovementOrDimensionChange() {
        var origin = new TeleportPosition("minecraft:overworld", 0, 64, 0, 0, 0);
        assertFalse(origin.moved(new TeleportPosition("minecraft:overworld", 0.001, 64, 0, 180, 90)));
        assertTrue(origin.moved(new TeleportPosition("minecraft:overworld", 0.1, 64, 0, 0, 0)));
        assertTrue(origin.moved(new TeleportPosition("minecraft:the_nether", 0, 64, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new TeleportPosition("bad", 0, 64, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TeleportPosition("minecraft:overworld", Double.NaN, 64, 0, 0, 0));
    }
}
