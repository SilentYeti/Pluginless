package com.silentyeti.pluginless;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** The same widget tree on both sides; all rank data and actions are authoritative on the server. */
public final class RankUI {
    private static final class Form {
        String rank = "default", player = "", duration = "", track = "", kit = "";
        String allow = "", deny = "", perk = "", value = "";
        Component status = Component.empty();
    }

    public static ModularUI create(Player player) {
        var form = new Form();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> overview(player)).build()),
                field("pluginless.rank.ui.rank", () -> form.rank, v -> form.rank = v),
                label().bind(DataBindingBuilder.componentS2C(() -> details(player, form.rank)).build()),
                field("pluginless.rank.ui.player", () -> form.player, v -> form.player = v),
                field("pluginless.rank.ui.duration", () -> form.duration, v -> form.duration = v),
                button("pluginless.rank.ui.set", player, () -> command(player, "rank set " + word(form.player) + " " + word(form.rank)
                        + (form.duration.isBlank() ? "" : " " + word(form.duration)))),
                field("pluginless.rank.ui.track", () -> form.track, v -> form.track = v),
                button("pluginless.rank.ui.promote", player, () -> command(player, "rank promote " + word(form.player) + " " + word(form.track))),
                button("pluginless.rank.ui.demote", player, () -> command(player, "rank demote " + word(form.player) + " " + word(form.track))),
                field("pluginless.rank.ui.kit", () -> form.kit, v -> form.kit = v),
                label().bind(DataBindingBuilder.componentS2C(() -> kitDetails(player, form.kit)).build()),
                button("pluginless.rank.ui.claim", player, () -> command(player, "kit " + word(form.kit))),
                label().setText(Component.translatable("pluginless.rank.ui.operator")),
                button("pluginless.rank.ui.load", player, () -> {
                    var rank = RankGate.ranks.ranks.get(form.rank);
                    if (rank == null || !canEdit(player)) return;
                    form.allow = String.join(", ", rank.allow);
                    form.deny = String.join(", ", rank.deny);
                    var value = rank.perks.get(form.perk);
                    form.value = value == null ? "" : value.toString();
                }),
                field("pluginless.rank.ui.allow", () -> form.allow, v -> form.allow = v),
                field("pluginless.rank.ui.deny", () -> form.deny, v -> form.deny = v),
                button("pluginless.rank.ui.rules", player, () -> edit(player, form, false)),
                field("pluginless.rank.ui.perk", () -> form.perk, v -> form.perk = v),
                field("pluginless.rank.ui.value", () -> form.value, v -> form.value = v),
                button("pluginless.rank.ui.save_perk", player, () -> edit(player, form, true)),
                label().bind(DataBindingBuilder.componentS2C(() -> form.status).build()));
        return screen(player, "pluginless.rank.ui.title", content);
    }

    static ModularUI screen(Player player, String title, UIElement content) {
        var scroll = new ScrollerView().viewContainer(view -> view.addChild(content));
        scroll.layout(layout -> layout.width(300).height(230));
        var root = new UIElement().addClass("panel_bg").layout(layout -> layout.paddingAll(7).gapAll(5)).addChildren(
                label().setText(Component.translatable(title)), scroll,
                button("pluginless.rank.ui.back", player, () -> command(player, "roth")));
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    static UIElement picker(String label, Player player, Supplier<String> get, Consumer<String> set, Supplier<List<String>> choices) {
        return new UIElement().addChildren(
                label().setText(Component.translatable(label)),
                label().bind(DataBindingBuilder.componentS2C(() -> Component.literal(get.get())).build()),
                button("pluginless.ui.previous", player, () -> pick(get, set, choices, -1)),
                button("pluginless.ui.next", player, () -> pick(get, set, choices, 1)));
    }

    static void pick(Supplier<String> get, Consumer<String> set, Supplier<List<String>> choices, int direction) {
        var values = choices.get();
        if (values.isEmpty()) return;
        int index = values.indexOf(get.get());
        set.accept(values.get(index < 0 ? 0 : Math.floorMod(index + direction, values.size())));
    }

    static Label label() {
        var label = new Label();
        label.textStyle(style -> style.adaptiveHeight(true).textWrap(TextWrap.WRAP));
        return label;
    }

    static UIElement field(String label, Supplier<String> get, Consumer<String> set) {
        return new UIElement().addChildren(label().setText(Component.translatable(label)),
                new TextField().bind(DataBindingBuilder.string(get, value -> {
                    if (value != null && value.length() <= 2048) set.accept(value);
                }).build()));
    }

    static Button button(String label, Player player, Runnable action) {
        return new Button().setText(Component.translatable(label)).setOnServerClick(event -> {
            if (event.button == 0 && player instanceof ServerPlayer) action.run();
        });
    }

    static String word(String text) {
        // One validated token; a menu must not be able to append an unrelated command or selector.
        return text.matches("[a-zA-Z0-9_+.-]+") ? text : "?";
    }

    static void command(Player player, String command) {
        if (player instanceof ServerPlayer serverPlayer) {
            var server = player.level().getServer();
            if (server != null) server.getCommands().performPrefixedCommand(serverPlayer.createCommandSourceStack(), command);
        }
    }

    private static boolean canEdit(Player player) {
        return player instanceof ServerPlayer serverPlayer && RankGate.isOperator(serverPlayer);
    }

    private static void edit(Player player, Form form, boolean perk) {
        if (!canEdit(player)) { form.status = Component.translatable("pluginless.rank.ui.denied"); return; }
        try {
            var gson = new Gson();
            var draft = gson.fromJson(gson.toJson(RankGate.ranks), Ranks.class);
            var rank = draft.ranks.get(form.rank);
            if (rank == null) { form.status = Component.translatable("pluginless.rank.unknown", form.rank); return; }
            if (perk) {
                if (form.perk.isBlank()) { form.status = Component.translatable("pluginless.rank.ui.invalid_perk"); return; }
                if (form.value.isBlank()) rank.perks.remove(form.perk);
                else {
                    var value = JsonParser.parseString(form.value);
                    if (!value.isJsonPrimitive() || !(value.getAsJsonPrimitive().isBoolean() || value.getAsJsonPrimitive().isNumber())) {
                        form.status = Component.translatable("pluginless.rank.ui.invalid_perk");
                        return;
                    }
                    rank.perks.put(form.perk, value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : value.getAsDouble());
                }
            } else {
                rank.allow = rules(form.allow);
                rank.deny = rules(form.deny);
            }
            var problem = save((ServerPlayer) player, draft);
            form.status = problem == null ? Component.translatable("pluginless.rank.ui.saved")
                    : Component.translatable("pluginless.rank.reload.failed", problem);
            if (problem == null) {
                player.level().getServer().getPlayerList().getPlayers().forEach(RankGate::refresh);
                Pluginless.LOGGER.info("rank config {} edited by {}", form.rank, player.getScoreboardName());
            }
        } catch (IllegalArgumentException | JsonParseException e) {
            form.status = Component.translatable("pluginless.rank.ui.invalid_perk");
        }
    }

    static String save(ServerPlayer player, Ranks draft) {
        return RankGate.isOperator(player) ? RankGate.save(draft) : Component.translatable("pluginless.rank.ui.denied").getString();
    }

    private static List<String> rules(String text) {
        return text.isBlank() ? List.of() : Arrays.stream(text.split(",")).map(String::trim).toList();
    }

    private static Component overview(Player player) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        var seconds = RankRewards.of(server, player.getUUID()).seconds();
        return Component.translatable("pluginless.rank.ui.overview", String.join(", ", RankGate.ranks.ranks.keySet()),
                RankGate.rankOf(server, player.getUUID()), seconds / 3600, (seconds / 60) % 60,
                String.join(", ", RankGate.ranks.tracks.keySet()), String.join(", ", RankGate.ranks.kits.keySet()));
    }

    static Component rewards(Player player, String rank) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        var result = Component.empty();
        var state = RankRewards.of(server, player.getUUID());
        for (var reward : RankGate.ranks.rewards(rank)) {
            result.append(Component.translatable("pluginless.rank.ui.reward", reward.id, reward.hours,
                    Config.format(Math.round(reward.money * 100)), reward.items.toString(),
                    Component.translatable(reward.repeat ? "pluginless.rank.ui.repeating" : "pluginless.rank.ui.once"),
                    state.rewards().getOrDefault(reward.id, 0L)));
        }
        return result;
    }

    private static Component kitDetails(Player player, String name) {
        var server = player.level().getServer();
        if (server == null || name.isBlank()) return Component.empty();
        var kit = RankGate.ranks.kits.get(name);
        if (kit == null || !(player instanceof ServerPlayer serverPlayer) || !RankRewards.canKit(server, serverPlayer, name)) {
            return Component.translatable("pluginless.kit.unavailable", name);
        }
        var last = RankRewards.of(server, player.getUUID()).kits().get(name);
        String rank = RankGate.rankOf(server, player.getUUID());
        long millis = Math.round(Math.max(0, RankGate.ranks.number(rank, "cooldown.kit." + name, kit.cooldown)) * 1000);
        long remaining = last == null ? 0 : Math.max(0, last + millis - System.currentTimeMillis());
        return Component.translatable("pluginless.rank.ui.kit_details", Config.format(Math.round(kit.money * 100)),
                kit.items.toString(), (remaining + 999) / 1000);
    }

    private static Component details(Player player, String name) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        var rank = RankGate.ranks.ranks.get(name);
        if (rank == null) return Component.translatable("pluginless.rank.unknown", name);
        var members = new java.util.TreeSet<String>();
        Players.known(server).forEach((member, id) -> { if (RankGate.rankOf(server, id).equals(name)) members.add(member); });
        RankGate.members(server).forEach((id, rankName) -> { if (RankGate.rankOf(server, id).equals(name)) members.add(Commands.name(server, id)); });
        return Component.translatable("pluginless.rank.ui.details", String.join(", ", members), String.join(", ", rank.allow),
                String.join(", ", rank.deny), RankGate.ranks.perks(name).toString()).append(rewards(player, name));
    }
}
