package com.silentyeti.pluginless;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One outstanding request per recipient; a new sender cannot replace an unanswered request. */
public final class Requests<T> {
    public record Request<T>(UUID sender, T value, long expires) {}
    private final Map<UUID, Request<T>> pending = new HashMap<>();

    public boolean put(UUID sender, UUID recipient, T value, long lifetime, long now) {
        if (sender.equals(recipient) || lifetime <= 0 || get(recipient, now) != null) return false;
        pending.put(recipient, new Request<>(sender, value, Math.addExact(now, lifetime)));
        return true;
    }

    public Request<T> get(UUID recipient, long now) {
        var request = pending.get(recipient);
        if (request != null && request.expires() <= now) { pending.remove(recipient); return null; }
        return request;
    }

    public Request<T> take(UUID recipient, long now) {
        var request = get(recipient, now);
        pending.remove(recipient);
        return request;
    }

    public void remove(UUID player) {
        pending.remove(player);
        pending.values().removeIf(request -> request.sender().equals(player));
    }
}
