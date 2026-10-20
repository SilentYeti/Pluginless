package com.silentyeti.pluginless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {
    @Test
    void intervalsRunOnceWithElapsedTimeAndDoNotReplayBacklogs() {
        var scheduler = new Scheduler();
        long[] seconds = {0};
        long[] minutes = {0};
        scheduler.every(1000, 0, elapsed -> seconds[0] += elapsed);
        scheduler.every(60_000, 0, elapsed -> minutes[0]++);
        scheduler.tick(999);
        assertEquals(0, seconds[0]);
        scheduler.tick(1000);
        assertEquals(1000, seconds[0]);
        scheduler.tick(65_000);
        assertEquals(65_000, seconds[0]);
        assertEquals(1, minutes[0]);
        scheduler.tick(65_000);
        assertEquals(1, minutes[0]);
        assertThrows(IllegalArgumentException.class, () -> scheduler.every(0, 0, elapsed -> {}));
    }
}
