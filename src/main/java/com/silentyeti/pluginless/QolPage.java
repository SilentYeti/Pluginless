package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Homes, warps, teleport requests and the other travel commands. Every intent runs the real command as the player, so the
 * rank rules, cooldowns, fees and warm-up are exactly the ones the command has. Setting or deleting warps stays a command.
 */
final class QolPage implements PdaPage {
    public String id() { return "qol"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var id = player.getUUID();
        var view = new JsonObject();
        double limit = Math.floor(Math.max(0, RankGate.ranks.number(RankGate.rankOf(server, id), "homes", 1)));
        view.addProperty("homeLimit", (int) limit);
        var homes = new JsonArray();
        Teleports.homesOf(server, id).entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
            var home = new JsonObject();
            var point = entry.getValue();
            home.addProperty("name", entry.getKey());
            home.addProperty("where", place(point));
            homes.add(home);
        });
        view.add("homes", homes);
        view.add("warps", PdaHub.strings(Teleports.warps(server)));
        var others = new JsonArray();
        for (var other : server.getPlayerList().getPlayers()) if (other != player) others.add(other.getScoreboardName());
        view.add("players", others);
        var request = Teleports.requests(server).get(id, System.currentTimeMillis());
        if (request != null) {
            var incoming = new JsonObject();
            incoming.addProperty("from", Commands.name(server, request.sender()));
            incoming.addProperty("here", request.value().here());
            view.add("request", incoming);
        }
        return view;
    }

    private static String place(TeleportPosition point) {
        var dimension = point.dimension().startsWith("minecraft:") ? point.dimension().substring(10) : point.dimension();
        return "%s %d, %d, %d".formatted(dimension, Math.round(point.x()), Math.round(point.y()), Math.round(point.z()));
    }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        var server = player.level().getServer();
        if (server == null) return Result.fail("Not available");
        var id = player.getUUID();
        String name = args.isEmpty() ? "" : args.get(0);
        String command = switch (verb) {
            case "home" -> Teleports.homesOf(server, id).containsKey(name) ? "home " + name : null;
            case "delhome" -> Teleports.homesOf(server, id).containsKey(name) ? "delhome " + name : null;
            case "sethome" -> FeatureSettings.key(name) ? "sethome " + name : null;
            case "warp" -> Teleports.warps(server).contains(name) ? "warp " + name : null;
            case "tpa", "tpahere" -> {
                var target = server.getPlayerList().getPlayerByName(name);
                yield target == null || target == player ? null : verb + " " + target.getScoreboardName();
            }
            case "tpaccept", "tpdeny", "back", "spawn", "rtp" -> verb;
            default -> null;
        };
        if (command == null) return Result.fail("Not possible");
        try {
            int done = server.getCommands().getDispatcher().execute(command, player.createCommandSourceStack());
            return done > 0 ? Result.ok(command.split(" ")[0].toUpperCase() + " OK") : Result.fail("Refused, see chat");
        } catch (CommandSyntaxException e) {
            return Result.fail("Not allowed for you");
        }
    }
}
