package com.silentyeti.pluginless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.List;

/** Cosmetic snapshots are advisory; every change runs through the gated player command again. */
final class CosmeticsPage implements PdaPage {
    public String id() { return "cosmetics"; }

    public JsonObject view(MinecraftServer server, ServerPlayer player) {
        var view = new JsonObject();
        var appearance = Cosmetics.of(server, player.getUUID());
        var rank = RankGate.ranks.ranks.get(RankGate.rankOf(server, player.getUUID()));
        view.addProperty("player", player.getUUID().toString());
        view.addProperty("realname", player.getScoreboardName());
        view.addProperty("prefix", rank.prefix);
        view.addProperty("suffix", rank.suffix);
        view.addProperty("nick", Cosmetics.allowed(player, "nick") && CosmeticNames.valid(appearance.nick(),
                Config.nickMaxLength(), Features.settings.blockedWords, List.of()) ? appearance.nick() : "");
        view.addProperty("color", !appearance.color().isEmpty() && Cosmetics.allowed(player,
                appearance.color().startsWith("<gradient:") ? "namegradient" : "namecolor") ? appearance.color() : rank.nameColor);
        view.addProperty("title", Cosmetics.allowed(player, "title." + appearance.title()) ? appearance.title() : "");
        view.addProperty("nick_limit", Config.nickMaxLength());
        view.addProperty("skull_price", Config.skullPriceCents());
        view.addProperty("head_empty", player.getItemBySlot(EquipmentSlot.HEAD).isEmpty());
        for (var perk : List.of("namecolor", "namegradient", "nick", "skull", "hat"))
            view.addProperty(perk + "_allowed", Cosmetics.allowed(player, perk));
        var titles = new JsonArray();
        Features.settings.titles.forEach((key, formatted) -> {
            var title = new JsonObject();
            title.addProperty("key", key);
            title.addProperty("label", Text.format(formatted).getString());
            title.addProperty("formatted", formatted);
            title.addProperty("allowed", Cosmetics.allowed(player, "title." + key));
            titles.add(title);
        });
        view.add("titles", titles);
        var slots = new JsonArray();
        for (int slot = 0; slot < 36; slot++) {
            var item = player.getInventory().getItem(slot);
            var entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("name", item.isEmpty() ? "Empty" : item.getHoverName().getString());
            entry.addProperty("count", item.getCount());
            slots.add(entry);
        }
        view.add("slots", slots);
        return view;
    }

    public Result act(ServerPlayer player, String verb, List<String> args, int slot) {
        try {
            return PdaCommands.run(player, command(verb, args, slot, player.getScoreboardName()));
        } catch (IllegalArgumentException e) {
            return Result.fail("pluginless.pda.invalid");
        }
    }

    /** A closed intent vocabulary prevents command or selector injection, including forged slot arguments. */
    private static String command(String verb, List<String> args, int slot, String realname) {
        if (verb.equals("hat") && args.isEmpty() && slot >= 0 && slot < 36) return "hat " + slot;
        if (slot != -1) throw new IllegalArgumentException("unexpected inventory slot");
        if (verb.equals("skull") && args.isEmpty()) return "skull " + realname;
        if (verb.equals("gradient") && args.size() == 2) {
            CosmeticNames.gradient(args.get(0), args.get(1));
            return "namegradient " + CosmeticNames.color(args.get(0)) + " " + CosmeticNames.color(args.get(1));
        }
        if (args.size() == 1) {
            String value = args.getFirst();
            if (verb.equals("color")) return "namecolor " + (value.equals("off") ? value : CosmeticNames.color(value));
            if (verb.equals("nick") && CosmeticNames.valid(value, 64, List.of(), List.of())) return "nick " + value;
            if (verb.equals("title") && value.matches("[a-zA-Z0-9_.+\\-]{1,64}")) return "nametitle " + value;
        }
        throw new IllegalArgumentException("unknown cosmetic intent");
    }
}
