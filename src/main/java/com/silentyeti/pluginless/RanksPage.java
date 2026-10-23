package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** PDA Ranks page: where you stand on the track, what the rank grants, kits you can claim and the playtime rewards. Staff tools stay on /rank. */
final class RanksPage implements PdaPage {
    static final int MAX_PERKS = 12;

    public String id() { return "ranks"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var id = player.getUUID();
        var ranks = RankGate.ranks;
        String rank = RankGate.rankOf(server, id);
        var state = RankRewards.of(server, id);
        var view = new JsonObject();
        view.addProperty("rank", rank);
        view.addProperty("seconds", state.seconds());
        view.addProperty("temporary", RankGate.temporary(server, id));

        // The first track this rank sits on; the next step and the hours it needs, when the track promotes by playtime.
        for (var entry : ranks.tracks.entrySet()) {
            var path = entry.getValue().ranks;
            if (!path.contains(rank)) continue;
            var track = new JsonObject();
            track.addProperty("name", entry.getKey());
            track.add("ranks", PdaHub.strings(path));
            track.addProperty("index", path.indexOf(rank));
            String next = ranks.step(entry.getKey(), rank, 1);
            if (next != null) {
                track.addProperty("next", next);
                var hours = entry.getValue().autoHours.get(next);
                if (hours != null) track.addProperty("nextSeconds", Math.round(hours * 3600));
            }
            view.add("track", track);
            break;
        }

        var perks = new JsonArray();
        for (var perk : ranks.perks(rank).entrySet()) {
            if (perks.size() >= MAX_PERKS) break;
            var row = new JsonObject();
            row.addProperty("name", perk.getKey());
            row.addProperty("value", perk.getValue() instanceof Number n ? trim(n.doubleValue()) : String.valueOf(perk.getValue()));
            perks.add(row);
        }
        view.add("perks", perks);

        long now = System.currentTimeMillis();
        var kits = new JsonArray();
        for (var kit : ranks.kits.entrySet()) {
            if (!RankRewards.canKit(server, player, kit.getKey())) continue;
            var last = state.kits().get(kit.getKey());
            long millis = Math.round(Math.max(0, ranks.number(rank, "cooldown.kit." + kit.getKey(), kit.getValue().cooldown)) * 1000);
            long left = last == null ? 0 : Math.max(0, last + millis - now);
            var row = grant(kit.getKey(), kit.getValue());
            row.addProperty("left", (left + 999) / 1000);
            kits.add(row);
        }
        view.add("kits", kits);

        var rewards = new JsonArray();
        for (var reward : ranks.rewards(rank)) {
            long claimed = state.rewards().getOrDefault(reward.id, 0L);
            long interval = Math.max(1, Math.round(reward.hours * 3600));
            var row = grant(reward.id, reward);
            row.addProperty("repeat", reward.repeat);
            row.addProperty("claimed", claimed);
            // Seconds until the next one is due; a one-off already claimed has none.
            boolean done = !reward.repeat && claimed > 0;
            row.addProperty("left", done ? -1 : Math.max(0, (claimed + 1) * interval - state.seconds()));
            rewards.add(row);
        }
        view.add("rewards", rewards);
        return view;
    }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        if (!verb.equals("kit") || args.size() != 1) return Result.fail("Unknown action");
        var name = args.get(0);
        // Same call the /kit command makes: it checks the rank gate, the cooldown and the inventory room itself.
        if (!RankGate.ranks.kits.containsKey(name)) return Result.fail("No such kit");
        return RankRewards.kit(player, name, System.currentTimeMillis()) ? Result.ok("Claimed " + name) : Result.fail("Kit " + name + " is not ready");
    }

    private static JsonObject grant(String id, Ranks.Grant grant) {
        var row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("money", Math.round(grant.money * 100));
        var items = new StringBuilder();
        for (Map.Entry<String, Integer> item : grant.items.entrySet()) {
            if (!items.isEmpty()) items.append(", ");
            String name = item.getKey().substring(item.getKey().indexOf(':') + 1).replace('_', ' ');
            items.append(item.getValue()).append(' ').append(name.toLowerCase(Locale.ROOT));
        }
        row.addProperty("items", items.toString());
        return row;
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
