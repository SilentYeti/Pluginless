package com.silentyeti.pluginless;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Execution bookkeeping, never used by permission checks or command suggestions. */
public final class Cooldowns {
    // shortcut: command cooldowns are session-local, persist them if restarts must retain rate limits
    private final Map<UUID, Map<String, Long>> lastUse = new HashMap<>();

    public long remaining(UUID player, String command, long millis, long now) {
        var last = lastUse.getOrDefault(player, Map.of()).get(command);
        return last == null || millis <= 0 ? 0 : Math.max(0, Math.addExact(last, millis) - now);
    }

    public boolean take(UUID player, String command, long millis, long now) {
        if (millis <= 0) return true;
        if (remaining(player, command, millis, now) > 0) return false;
        lastUse.computeIfAbsent(player, key -> new HashMap<>()).put(command, now);
        return true;
    }

}
