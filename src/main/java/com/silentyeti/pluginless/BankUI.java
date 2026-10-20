package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;

import static com.silentyeti.pluginless.RankUI.*;

public final class BankUI {
    private static final class Form { String target = "", amount = ""; }

    public static ModularUI create(Player player) {
        var form = new Form();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> summary(player)).build()),
                picker("pluginless.bank.player", player, () -> form.target, v -> form.target = v, () -> players(player)),
                field("pluginless.bank.amount", () -> form.amount, v -> form.amount = v),
                button("pluginless.bank.send", player, () -> command(player, "pay " + word(form.target) + " " + word(form.amount))),
                button("pluginless.bank.request", player, () -> command(player, "bank request " + word(form.target) + " " + word(form.amount))),
                label().bind(DataBindingBuilder.componentS2C(() -> player instanceof ServerPlayer p ? Bank.pending(p) : Component.empty()).build()),
                button("pluginless.bank.accept", player, () -> command(player, "bank accept")),
                button("pluginless.bank.decline", player, () -> command(player, "bank decline")),
                button("pluginless.bank.borrow", player, () -> command(player, "bank borrow " + word(form.amount))),
                button("pluginless.bank.repay", player, () -> command(player, "bank repay " + word(form.amount))),
                label().bind(DataBindingBuilder.componentS2C(() -> player.level().getServer() == null ? Component.empty()
                        : Bank.history(player.level().getServer(), player.getUUID())).build()));
        return screen(player, "pluginless.bank.title", content);
    }

    static List<String> players(Player player) {
        var server = player.level().getServer();
        if (server == null) return List.of();
        var names = new java.util.TreeSet<String>();
        Players.known(server).forEach((name, id) -> { if (!id.equals(player.getUUID())) names.add(name); });
        server.getPlayerList().getPlayers().forEach(online -> { if (!online.getUUID().equals(player.getUUID())) names.add(online.getScoreboardName()); });
        return List.copyOf(names);
    }

    static Component summary(Player player) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        return Component.translatable("pluginless.bank.summary", Config.format(Ledger.of(server).balance(player.getUUID())),
                Config.format(Bank.debt(server, player.getUUID())), Config.format(Bank.limit(server, player.getUUID())), Config.loanDailyInterest());
    }
}
