package com.silentyeti.pluginless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.serialization.Codec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Applies the rank configuration to the running server: who has which rank, and what that lets them run. */
public final class RankGate {
    private static final Codec<Map<UUID, String>> ASSIGNMENTS =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).xmap(HashMap::new, map -> map);
    private static final Field REQUIREMENT;

    static {
        try {
            // Brigadier offers no way to change a registered command's permission check, and this has to
            // work for every command of every mod, so the check is replaced in place.
            REQUIREMENT = CommandNode.class.getDeclaredField("requirement");
            REQUIREMENT.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Ranks ranks = defaults();

    /** Reads config/pluginless/ranks.json, creating it on first run. Returns what is wrong with it, or null. */
    static String load() {
        var file = FMLPaths.CONFIGDIR.get().resolve(Pluginless.MOD_ID).resolve("ranks.json");
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, gson.toJson(defaults()));
            }
            var loaded = gson.fromJson(Files.readString(file), Ranks.class);
            var problem = loaded == null || loaded.ranks == null ? "the file is empty" : loaded.problem();
            // A broken file keeps the ranks that were already in force; it is never overwritten.
            if (problem == null) ranks = loaded;
            return problem;
        } catch (IOException | JsonParseException e) {
            return e.getMessage();
        }
    }

    private static Ranks defaults() {
        var ranks = new Ranks();
        ranks.ranks.put("default", new Ranks.Rank());
        var moderator = new Ranks.Rank();
        moderator.inherits = "default";
        moderator.prefix = "&9[Mod] ";
        moderator.allow = java.util.List.of("kick", "ban", "pardon", "tp", "teleport");
        ranks.ranks.put("moderator", moderator);
        var admin = new Ranks.Rank();
        admin.inherits = "moderator";
        admin.prefix = "&c[Admin] ";
        admin.allow = java.util.List.of("*");
        ranks.ranks.put("admin", admin);
        return ranks;
    }

    static String rankOf(MinecraftServer server, UUID player) {
        var rank = assignments(server).value.get(player);
        return rank != null && ranks.ranks.containsKey(rank) ? rank : ranks.defaultRank;
    }

    static void setRank(MinecraftServer server, UUID player, String rank) {
        var store = assignments(server);
        store.value.put(player, rank);
        store.setDirty();
        var online = server.getPlayerList().getPlayer(player);
        if (online != null) refresh(online);
    }

    /** Makes a player's client and name catch up with a rank or configuration change. */
    static void refresh(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null) return;
        server.getCommands().sendCommands(player);
        player.refreshDisplayName();
        player.refreshTabListName();
    }

    static Component withPrefix(ServerPlayer player, Component name) {
        var server = player.level().getServer();
        if (server == null) return name;
        var prefix = ranks.prefix(rankOf(server, player.getUUID())).replace('&', '§');
        return prefix.isEmpty() ? name : Component.literal(prefix).append(name);
    }

    private static Store<Map<UUID, String>> assignments(MinecraftServer server) {
        return Store.open(server, "ranks", ASSIGNMENTS, HashMap::new);
    }

    /** Puts the rank rules in front of every command's own permission check. Run after all mods registered theirs. */
    static void install(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (var command : dispatcher.getRoot().getChildren()) {
            gate(command, command.getName());
        }
    }

    private static void gate(CommandNode<CommandSourceStack> node, String name) {
        if (node instanceof LiteralCommandNode) {
            var original = node.getRequirement();
            Predicate<CommandSourceStack> gated = source -> allowed(source, name, original);
            try {
                REQUIREMENT.set(node, gated);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        for (var child : node.getChildren()) {
            gate(child, child instanceof LiteralCommandNode ? name + " " + child.getName() : name);
        }
    }

    private static boolean allowed(CommandSourceStack source, String command, Predicate<CommandSourceStack> original) {
        // Rank rules are for players. Operators are exempt so a bad configuration cannot lock everyone out.
        // "/execute as <player>" also lands here with that player's rank, whoever ran it.
        if (!(source.getEntity() instanceof ServerPlayer player) || isOperator(player)) return original.test(source);
        var verdict = ranks.verdict(rankOf(source.getServer(), player.getUUID()), command);
        return verdict != null ? verdict : original.test(source);
    }

    private static boolean isOperator(ServerPlayer player) {
        var server = player.level().getServer();
        //? if >=26.1 {
        /*return server != null && server.getPlayerList().isOp(player.nameAndId());
        *///?} else {
        return server != null && server.getPlayerList().isOp(player.getGameProfile());
        //?}
    }
}
