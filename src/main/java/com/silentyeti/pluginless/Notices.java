package com.silentyeti.pluginless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What happened to a player while they were not looking: the PDA home shows the latest and badges the unread count.
 * History only, no toast. Kept in memory (a restart clears it), at most {@link #LIMIT} per player.
 */
public final class Notices {
    public static final int LIMIT = 32;
    public record Notice(long time, String kind, String text, boolean unread) {}

    private static final class History {
        final Deque<Notice> items = new ArrayDeque<>();
        int unread;
    }

    private static final Map<UUID, History> HISTORY = new HashMap<>();

    public static synchronized void push(UUID player, String kind, String text, long now) {
        var history = HISTORY.computeIfAbsent(player, id -> new History());
        history.items.addFirst(new Notice(now, kind, text, true));
        history.unread++;
        while (history.items.size() > LIMIT) {
            var dropped = history.items.removeLast();
            if (dropped.unread()) history.unread--;
        }
    }

    /** Newest first. */
    public static synchronized List<Notice> list(UUID player, int max) {
        var history = HISTORY.get(player);
        if (history == null) return List.of();
        var result = new ArrayList<Notice>();
        for (var notice : history.items) {
            if (result.size() >= max) break;
            result.add(notice);
        }
        return result;
    }

    public static synchronized int unread(UUID player) {
        var history = HISTORY.get(player);
        return history == null ? 0 : history.unread;
    }

    public static synchronized void markRead(UUID player) {
        var history = HISTORY.get(player);
        if (history == null || history.unread == 0) return;
        var read = new ArrayDeque<Notice>();
        for (var notice : history.items) read.add(new Notice(notice.time(), notice.kind(), notice.text(), false));
        history.items.clear();
        history.items.addAll(read);
        history.unread = 0;
    }

    public static synchronized void clear() {
        HISTORY.clear();
    }
}
