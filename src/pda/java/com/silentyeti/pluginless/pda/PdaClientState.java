package com.silentyeti.pluginless.pda;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * What the server last told this client, per page, plus which page is open. Plain data (no WFLib), so the packet
 * handlers can write it from any thread-safe point and the app reads it every frame.
 */
public final class PdaClientState {
    private static final Map<String, JsonObject> VIEWS = new HashMap<>();
    private static volatile String page = "home";
    private static volatile String message = "";
    private static volatile boolean messageOk = true;
    private static volatile long messageTime;
    /** How long a result line stays on screen. */
    public static final long MESSAGE_MILLIS = 5000;

    private PdaClientState() {}

    static void view(String id, String json) {
        try {
            var parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) synchronized (VIEWS) { VIEWS.put(id, parsed.getAsJsonObject()); }
        } catch (RuntimeException ignored) {
            // A malformed snapshot is dropped; the previous one stays on screen.
        }
    }

    static void result(String id, boolean ok, String text) {
        if (text.isEmpty()) return;
        // Keys (pluginless.pda.*, command replies) are translated here; plain text passes through unchanged.
        message = net.minecraft.network.chat.Component.translatable(text).getString();
        messageOk = ok;
        messageTime = System.currentTimeMillis();
    }

    public static JsonObject view(String id) {
        synchronized (VIEWS) { return VIEWS.getOrDefault(id, new JsonObject()); }
    }

    public static String page() { return page; }

    public static void page(String id) { page = id; }

    public static String message() { return System.currentTimeMillis() - messageTime < MESSAGE_MILLIS ? message : ""; }

    public static boolean messageOk() { return messageOk; }

    public static void reset() {
        synchronized (VIEWS) { VIEWS.clear(); }
        page = "home";
        message = "";
    }

    public static long number(JsonObject view, String key) {
        var element = view.get(key);
        return element == null || !element.isJsonPrimitive() ? 0 : element.getAsLong();
    }

    public static String text(JsonObject view, String key) {
        var element = view.get(key);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    /** Cents in the server's currency, e.g. {@code $12,480.00}. */
    public static String money(JsonObject view, long cents) {
        var symbol = text(view, "symbol");
        var sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return "%s%s%,d.%02d".formatted(sign, symbol.isEmpty() ? "$" : symbol, abs / 100, abs % 100);
    }
}
