package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;

import static com.silentyeti.pluginless.RankUI.*;

public final class CosmeticsUI {
    private static final List<String> COLORS = List.of("#000000", "#0000aa", "#00aa00", "#00aaaa", "#aa0000", "#aa00aa", "#ffaa00", "#aaaaaa",
            "#555555", "#5555ff", "#55ff55", "#55ffff", "#ff5555", "#ff55ff", "#ffff55", "#ffffff");
    private static final class Form { String nick = "", color = "#ffffff", from = "#ff0000", to = "#0000ff", title = "off", head = ""; }

    public static ModularUI create(Player player) {
        var form = new Form();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> preview(player, form, false)).build()),
                label().bind(DataBindingBuilder.componentS2C(() -> preview(player, form, true)).build()),
                label().bind(DataBindingBuilder.componentS2C(() -> player instanceof ServerPlayer p ? Component.translatable("pluginless.cosmetic.unlocks",
                        Cosmetics.allowed(p, "nick"), Cosmetics.allowed(p, "namecolor"), Cosmetics.allowed(p, "namegradient"), Cosmetics.allowed(p, "skull"), Cosmetics.allowed(p, "hat")) : Component.empty()).build()),
                field("pluginless.cosmetic.nick", () -> form.nick, v -> form.nick = v),
                button("pluginless.cosmetic.setnick", player, () -> command(player, "nick " + word(form.nick))),
                button("pluginless.cosmetic.resetnick", player, () -> command(player, "nick off")),
                picker("pluginless.cosmetic.color", player, () -> form.color, v -> form.color = v, () -> COLORS),
                field("pluginless.cosmetic.hex", () -> form.color, v -> form.color = v),
                button("pluginless.cosmetic.applycolor", player, () -> command(player, "namecolor " + color(form.color))),
                picker("pluginless.cosmetic.from", player, () -> form.from, v -> form.from = v, () -> COLORS),
                field("pluginless.cosmetic.hex", () -> form.from, v -> form.from = v),
                picker("pluginless.cosmetic.to", player, () -> form.to, v -> form.to = v, () -> COLORS),
                field("pluginless.cosmetic.hex", () -> form.to, v -> form.to = v),
                button("pluginless.cosmetic.applygradient", player, () -> command(player, "namegradient " + color(form.from) + " " + color(form.to))),
                button("pluginless.cosmetic.resetcolor", player, () -> command(player, "namecolor off")),
                picker("pluginless.cosmetic.title", player, () -> form.title, v -> form.title = v, () -> {
                    var titles = new java.util.ArrayList<String>(); titles.add("off"); titles.addAll(Features.settings.titles.keySet()); return titles;
                }),
                button("pluginless.cosmetic.applytitle", player, () -> command(player, "nametitle " + word(form.title))),
                picker("pluginless.cosmetic.skull", player, () -> form.head, v -> form.head = v, () -> BankUI.players(player)),
                field("pluginless.cosmetic.skull_name", () -> form.head, v -> form.head = v),
                label().bind(DataBindingBuilder.componentS2C(() -> Component.translatable("pluginless.cosmetic.skull_price", Config.format(Config.skullPriceCents()))).build()),
                button("pluginless.cosmetic.getskull", player, () -> command(player, "skull " + word(form.head))),
                button("pluginless.cosmetic.hat", player, () -> command(player, "hat")));
        return screen(player, "pluginless.cosmetic.screen", content);
    }

    private static String color(String value) {
        try { return CosmeticNames.color(value); } catch (IllegalArgumentException e) { return "?"; }
    }

    private static Component preview(Player player, Form form, boolean gradient) {
        if (!(player instanceof ServerPlayer p)) return Component.empty();
        try {
            String name = form.nick.isEmpty() ? player.getScoreboardName() : form.nick;
            if (!CosmeticNames.valid(name, Config.nickMaxLength(), Features.settings.blockedWords, List.of())) return Component.translatable("pluginless.cosmetic.failed");
            String setting = gradient ? CosmeticNames.gradient(form.from, form.to) : CosmeticNames.color(form.color);
            var result = Component.translatable(gradient ? "pluginless.cosmetic.preview_gradient" : "pluginless.cosmetic.preview_color")
                    .append(Text.format(Formatting.colorize(setting, name)));
            String title = Features.settings.titles.get(form.title);
            if (title != null) result.append(Text.format(" " + title)).append(Component.translatable("pluginless.cosmetic.title_access", Cosmetics.allowed(p, "title." + form.title)));
            return result;
        } catch (IllegalArgumentException e) { return Component.translatable("pluginless.cosmetic.failed"); }
    }
}
