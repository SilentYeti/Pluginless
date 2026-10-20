package com.silentyeti.pluginless;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/** One server-thread timer; elapsed time is supplied to tasks, without replaying a backlog. */
public final class Scheduler {
    private static final class Task {
        final long interval;
        final LongConsumer action;
        long last;

        Task(long interval, long now, LongConsumer action) {
            if (interval <= 0) throw new IllegalArgumentException("interval must be positive");
            this.interval = interval;
            this.last = now;
            this.action = action;
        }
    }

    private final List<Task> tasks = new ArrayList<>();

    public void every(long interval, long now, LongConsumer action) {
        tasks.add(new Task(interval, now, action));
    }

    public void tick(long now) {
        for (var task : List.copyOf(tasks)) {
            if (now - task.last < task.interval) continue;
            long elapsed = now - task.last;
            task.last = now;
            task.action.accept(elapsed);
        }
    }
}
