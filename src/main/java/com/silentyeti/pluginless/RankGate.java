package com.silentyeti.pluginless;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.WeakHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Applies the rank configuration to the running server: who has which rank, and what that lets them run. */
public final class RankGate {
    private static final Codec<Map<UUID, String>> ASSIGNMENTS =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).xmap(HashMap::new, map -> map);
    private static final Field REQUIREMENT;
    private static final Field COMMAND;
    private static final Map<MinecraftServer, Cooldowns> COOLDOWNS = new WeakHashMap<>();

    record Timed(String previous, long expires) {
        static final Codec<Timed> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("previous").forGetter(Timed::previous),
                Codec.LONG.fieldOf("expires").forGetter(Timed::expires)
        ).apply(instance, Timed::new));
    }

    private static Store<Map<UUID, Timed>> timed(MinecraftServer server) {
        return Store.open(server, "timed_ranks", Codec.unboundedMap(UUIDUtil.STRING_CODEC, Timed.CODEC)
                .xmap(HashMap::new, map -> map), HashMap::new);
    }

    static {
        try {
            // Brigadier offers no way to change a registered command's permission check, and this has to
            // work for every command of every mod, so the check is replaced in place.
            REQUIREMENT = CommandNode.class.getDeclaredField("requirement");
            REQUIREMENT.setAccessible(true);
            COMMAND = CommandNode.class.getDeclaredField("command");
            COMMAND.setAccessible(true);
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
            if (problem == null) problem = RankRewards.configProblem(loaded);
            if (problem == null) ranks = loaded;
            return problem;
        } catch (IOException | JsonParseException e) {
            return e.getMessage();
        }
    }

    /** Save edits atomically; never replace a broken file or apply an invalid draft. */
    static String save(Ranks draft) {
        var problem = draft.problem();
        if (problem == null) problem = RankRewards.configProblem(draft);
        if (problem != null) return problem;
        var file = FMLPaths.CONFIGDIR.get().resolve(Pluginless.MOD_ID).resolve("ranks.json");
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        java.nio.file.Path temp = null;
        try {
            if (Files.exists(file)) {
                var disk = gson.fromJson(Files.readString(file), Ranks.class);
                if (disk == null || disk.problem() != null || RankRewards.configProblem(disk) != null) return "the existing file is broken";
            }
            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), "ranks-", ".json");
            Files.writeString(temp, gson.toJson(draft));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            ranks = draft;
            return null;
        } catch (IOException | JsonParseException e) {
            return e.getMessage();
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException e) { Pluginless.LOGGER.warn("could not remove rank draft", e); }
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
        var leases = timed(server);
        if (leases.value.remove(player) != null) leases.setDirty();
        applyRank(server, player, rank);
    }

    static void setRank(MinecraftServer server, UUID player, String rank, long duration, long now) {
        var leases = timed(server);
        var old = leases.value.get(player);
        // Replacing a timed grant keeps its permanent base rather than making a temporary rank permanent.
        String previous = old == null ? rankOf(server, player) : old.previous();
        leases.value.put(player, new Timed(previous, Math.addExact(now, duration)));
        leases.setDirty();
        applyRank(server, player, rank);
    }

    static void expire(MinecraftServer server, long now) {
        var leases = timed(server);
        for (var entry : List.copyOf(leases.value.entrySet())) {
            if (entry.getValue().expires() <= now) {
                setRank(server, entry.getKey(), ranks.ranks.containsKey(entry.getValue().previous()) ? entry.getValue().previous() : ranks.defaultRank);
            }
        }
    }

    static void move(MinecraftServer server, UUID from, UUID to) {
        var store = assignments(server);
        var rank = store.value.remove(from);
        if (rank != null) { store.value.put(to, rank); store.setDirty(); }
        var leases = timed(server);
        var lease = leases.value.remove(from);
        if (lease != null) { leases.value.put(to, lease); leases.setDirty(); }
        RankRewards.move(server, from, to);
    }

    static Map<UUID, String> members(MinecraftServer server) {
        return Map.copyOf(assignments(server).value);
    }

    static boolean temporary(MinecraftServer server, UUID player) {
        return timed(server).value.containsKey(player);
    }

    private static void applyRank(MinecraftServer server, UUID player, String rank) {
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
        var rank = ranks.ranks.get(rankOf(server, player.getUUID()));
        return Text.format(rank.prefix).append(Cosmetics.name(player, rank.nameColor, name)).append(Cosmetics.title(player)).append(Text.format(rank.suffix));
    }

    static Component chat(ServerPlayer player, Component message) {
        var server = player.level().getServer();
        if (server == null) return message;
        String name = rankOf(server, player.getUUID());
        var rank = ranks.ranks.get(name);
        String template = ranks.chatFormat(name);
        if (template == null) return null;
        var result = Component.empty();
        var tokens = java.util.regex.Pattern.compile("\\{(prefix|name|suffix|message)\\}").matcher(template);
        int last = 0;
        while (tokens.find()) {
            result.append(Text.format(template.substring(last, tokens.start())));
            result.append(switch (tokens.group(1)) {
                case "prefix" -> Text.format(rank.prefix);
                case "suffix" -> Text.format(rank.suffix);
                case "name" -> Cosmetics.name(player, rank.nameColor).copy().append(Cosmetics.title(player));
                // Player text is literal; configured message colours cannot turn chat into format markup.
                default -> coloredMessage(rank.messageColor, message);
            });
            last = tokens.end();
        }
        return result.append(Text.format(template.substring(last)));
    }

    static Component message(ServerPlayer player, Component message) {
        var server = player.level().getServer();
        if (server == null) return message;
        return coloredMessage(ranks.ranks.get(rankOf(server, player.getUUID())).messageColor, message);
    }

    private static Component coloredMessage(String setting, Component message) {
        if (setting.isEmpty()) return message.copy();
        // Parsing only the trusted colour setting keeps & codes in player messages literal.
        var spans = Formatting.parse(Formatting.colorize(setting, "x"));
        var color = spans.stream().filter(span -> !span.text().isEmpty()).findFirst().map(Formatting.Span::color).orElse(null);
        return color == null ? message.copy() : message.copy().withStyle(style -> style.withColor(color));
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
        var action = node.getCommand();
        if (action != null) {
            Command<CommandSourceStack> wrapped = context -> {
                var source = context.getSource();
                if (!(source.getEntity() instanceof ServerPlayer player) || isOperator(player)) return action.run(context);
                String key = ranks.cooldownKey(name);
                if (key == null) return action.run(context);
                long now = System.currentTimeMillis();
                long millis = ranks.cooldownMillis(rankOf(source.getServer(), player.getUUID()), key);
                var cooldowns = COOLDOWNS.computeIfAbsent(source.getServer(), server -> new Cooldowns());
                long remaining = millis == 0 ? 0 : cooldowns.remaining(player.getUUID(), key, millis, now);
                if (remaining > 0) {
                    source.sendFailure(Component.translatable("pluginless.cooldown", (remaining + 999) / 1000));
                    return 0;
                }
                int result = action.run(context);
                if (result > 0) cooldowns.take(player.getUUID(), key, millis, now);
                return result;
            };
            try { COMMAND.set(node, wrapped); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
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
            // The server account literal represents a target argument, not an extra permission or cooldown.
            gate(child, child instanceof LiteralCommandNode && !child.getName().equals("@server") ? name + " " + child.getName() : name);
        }
    }

    private static boolean allowed(CommandSourceStack source, String command, Predicate<CommandSourceStack> original) {
        // Rank rules are for players. Operators are exempt so a bad configuration cannot lock everyone out.
        // "/execute as <player>" also lands here with that player's rank, whoever ran it.
        if (!(source.getEntity() instanceof ServerPlayer player) || isOperator(player)) return original.test(source);
        var verdict = ranks.verdict(rankOf(source.getServer(), player.getUUID()), command);
        return verdict != null ? verdict : original.test(source);
    }

    static boolean isOperator(ServerPlayer player) {
        var server = player.level().getServer();
        //? if >=26.1 {
        /*return server != null && server.getPlayerList().isOp(player.nameAndId());
        *///?} else {
        return server != null && server.getPlayerList().isOp(player.getGameProfile());
        //?}
    }
}
