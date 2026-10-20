package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;

import static com.silentyeti.pluginless.RankUI.*;

public final class QolUI {
    private static final class Form { String home = "home", warp = "", target = ""; }
    public static ModularUI create(Player player) {
        var form = new Form();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> overview(player)).build()),
                button("pluginless.qol.spawn", player, () -> command(player, "spawn")),
                button("pluginless.qol.setspawn", player, () -> command(player, "setspawn")),
                picker("pluginless.qol.home", player, () -> form.home, v -> form.home = v, () -> player.level().getServer() == null ? List.of()
                        : Teleports.homesOf(player.level().getServer(), player.getUUID()).keySet().stream().sorted().toList()),
                field("pluginless.qol.home_name", () -> form.home, v -> form.home = v),
                button("pluginless.qol.gohome", player, () -> command(player, "home " + word(form.home))),
                button("pluginless.qol.sethome", player, () -> command(player, "sethome " + word(form.home))),
                button("pluginless.qol.delhome", player, () -> command(player, "delhome " + word(form.home))),
                picker("pluginless.qol.player", player, () -> form.target, v -> form.target = v, () -> player.level().getServer() == null ? List.of()
                        : player.level().getServer().getPlayerList().getPlayers().stream().filter(p -> !p.getUUID().equals(player.getUUID())).map(ServerPlayer::getScoreboardName).sorted().toList()),
                button("pluginless.qol.tpa", player, () -> command(player, "tpa " + word(form.target))),
                button("pluginless.qol.tpahere", player, () -> command(player, "tpahere " + word(form.target))),
                label().bind(DataBindingBuilder.componentS2C(() -> player instanceof ServerPlayer p ? Teleports.pending(p) : Component.empty()).build()),
                button("pluginless.qol.tpaccept", player, () -> command(player, "tpaccept")),
                button("pluginless.qol.tpdeny", player, () -> command(player, "tpdeny")),
                button("pluginless.qol.rtp", player, () -> command(player, "rtp")),
                button("pluginless.qol.back", player, () -> command(player, "back")),
                picker("pluginless.qol.warp", player, () -> form.warp, v -> form.warp = v, () -> player.level().getServer() == null ? List.of() : Teleports.warps(player.level().getServer())),
                field("pluginless.qol.warp_name", () -> form.warp, v -> form.warp = v),
                button("pluginless.qol.gowarp", player, () -> command(player, "warp " + word(form.warp))),
                button("pluginless.qol.setwarp", player, () -> command(player, "setwarp " + word(form.warp))),
                button("pluginless.qol.delwarp", player, () -> command(player, "delwarp " + word(form.warp))));
        return screen(player, "pluginless.qol.title", content);
    }

    private static Component overview(Player player) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        String rank = RankGate.rankOf(server, player.getUUID());
        return Component.translatable("pluginless.qol.overview", String.join(", ", Teleports.homesOf(server, player.getUUID()).keySet()),
                Math.floor(Math.max(0, RankGate.ranks.number(rank, "homes", 1))),
                RankGate.ranks.flag(rank, "teleport_no_warmup") ? 0 : Config.teleportWarmupSeconds(), Config.rtpRadius())
                .append(Component.translatable("pluginless.qol.costs", Features.settings.commandCosts.entrySet().stream()
                        .filter(entry -> entry.getValue() > 0).map(entry -> Component.translatable("pluginless.qol.cost", entry.getKey(), Config.format(Features.cost(entry.getKey()))).getString())
                        .collect(java.util.stream.Collectors.joining(", "))));
    }
}
