package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurationsTest {
    @Test
    void unitsAndInvalidDurations() {
        assertEquals(2_592_000_000L, Durations.millis("30d"));
        assertEquals(1000, Durations.millis("1s"));
        assertEquals(60_000, Durations.millis("1m"));
        assertEquals(3_600_000, Durations.millis("1h"));
        assertEquals(604_800_000, Durations.millis("1w"));
        for (var bad : new String[]{"0d", "-1d", "1", "1y", "1.5d", "999999999999999999999d", "9223372036854775807d"}) {
            assertThrows(IllegalArgumentException.class, () -> Durations.millis(bad));
        }
    }
}
