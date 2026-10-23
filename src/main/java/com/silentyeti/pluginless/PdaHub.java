package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server end of the PDA app: which page each player has open, what to push to them, and where their intents go.
 * Version-neutral; the packets that carry it live in the 1.21.1 front end ({@link Sender}).
 */
public final class PdaHub {
    /** Carries snapshots and results to one client. */
    public interface Sender {
        void view(ServerPlayer player, String page, String json);
        void result(ServerPlayer player, String page, boolean ok, String message);
    }

    /** At most this many intents per player per second; the rest are refused. */
    static final int RATE = 10;
    static final int REFRESH_TICKS = 20;
    public static final int MAX_ARGS = 8;
    public static final int MAX_ARG_LENGTH = 64;

    private static final class Session {
        String page;
        String last = "";
        long window;
        int used;
    }

    private static final Map<String, PdaPage> PAGES = new LinkedHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static Sender sender;
    private static int ticks;

    private PdaHub() {}

    public static void sender(Sender value) { sender = value; }

    public static synchronized void register(PdaPage page) {
        if (PAGES.putIfAbsent(page.id(), page) != null) throw new IllegalStateException("PDA page already registered: " + page.id());
    }

    public static synchronized List<String> pages() { return List.copyOf(PAGES.keySet()); }

    /** The client opened a page; it gets a snapshot at once. */
    public static synchronized void open(ServerPlayer player, String id) {
        var page = PAGES.get(id);
        if (page == null) return;
        var session = SESSIONS.computeIfAbsent(player.getUUID(), key -> new Session());
        session.page = id;
        session.last = "";
        push(player, session, page);
    }

    public static synchronized void close(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    /** One intent. Unknown pages and verbs, flooding and oversized arguments do nothing. */
    public static synchronized void act(ServerPlayer player, String id, String verb, List<String> args, int slot, long now) {
        var page = PAGES.get(id);
        if (page == null || sender == null) return;
        var session = SESSIONS.computeIfAbsent(player.getUUID(), key -> new Session());
        if (now - session.window >= 1000) { session.window = now; session.used = 0; }
        if (++session.used > RATE) { sender.result(player, id, false, "pluginless.pda.slow"); return; }
        if (verb.isEmpty() || verb.length() > MAX_ARG_LENGTH || args.size() > MAX_ARGS || args.stream().anyMatch(a -> a.length() > MAX_ARG_LENGTH)
                || slot < -1 || slot > 40) {
            sender.result(player, id, false, "pluginless.pda.invalid");
            return;
        }
        PdaPage.Result result;
        try {
            result = page.act(player, verb, args, slot);
        } catch (RuntimeException e) {
            Pluginless.LOGGER.error("PDA page {} failed on {}", id, verb, e);
            result = PdaPage.Result.fail("pluginless.pda.error");
        }
        sender.result(player, id, result.ok(), result.message());
        session.page = id;
        push(player, session, page);
    }

    /** Every {@link #REFRESH_TICKS} ticks: players with a page open get a new snapshot if it changed. */
    public static synchronized void tick(MinecraftServer server) {
        if (++ticks % REFRESH_TICKS != 0) return;
        for (var player : server.getPlayerList().getPlayers()) {
            var session = SESSIONS.get(player.getUUID());
            var page = session == null ? null : PAGES.get(session.page);
            if (page != null) push(player, session, page);
        }
    }

    private static void push(ServerPlayer player, Session session, PdaPage page) {
        if (sender == null) return;
        var server = player.level().getServer();
        if (server == null) return;
        JsonObject view;
        try {
            view = page.view(server, player);
        } catch (RuntimeException e) {
            Pluginless.LOGGER.error("PDA page {} failed to build its view", page.id(), e);
            return;
        }
        // Every page carries the balance and the unread count for the top bar.
        view.addProperty("balance", Ledger.of(server).balance(player.getUUID()));
        view.addProperty("unread", Notices.unread(player.getUUID()));
        view.addProperty("symbol", Config.symbol());
        var json = view.toString();
        if (json.equals(session.last)) return;
        session.last = json;
        sender.view(player, page.id(), json);
    }

    public static synchronized void left(ServerPlayer player) { SESSIONS.remove(player.getUUID()); }

    public static synchronized void stopped() { SESSIONS.clear(); Notices.clear(); }

    static JsonArray strings(Iterable<String> values) {
        var array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
