package com.silentyeti.pluginless;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * One page of the PDA app. The same books and rules as the commands: a page reads them into a snapshot and turns a
 * client intent into the same call a command makes. The client never says what a price or balance is.
 */
public interface PdaPage {
    /** Result of one intent; the message is shown on the page and must not carry secrets. */
    record Result(boolean ok, String message) {
        public static Result ok(String message) { return new Result(true, message); }
        public static Result fail(String message) { return new Result(false, message); }
    }

    String id();

    /** Everything the page shows for this player. Called while the page is open, so keep it cheap and paged. */
    JsonObject view(MinecraftServer server, ServerPlayer player);

    /** One intent from the client; {@code slot} is an inventory slot the player picked, or -1. */
    Result act(ServerPlayer player, String verb, List<String> args, int slot);
}
