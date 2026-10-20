package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** The terminal home screen. Built identically for the held item and the /roth command. */
public final class TerminalUI {
    public static ModularUI create(Player player) {
        var root = new UIElement().addClass("panel_bg")
                .layout(layout -> layout.paddingAll(7).gapAll(5))
                .addChildren(
                        new Label().setText(Component.translatable("pluginless.terminal")),
                        // server-to-client only: a client must never be able to write a balance
                        new Label().bind(DataBindingBuilder.componentS2C(() -> balance(player)).build()),
                        RankUI.button("pluginless.ui.balance", player, () -> RankUI.command(player, "balance gui")),
                        RankUI.button("pluginless.bank.title", player, () -> RankUI.command(player, "bank")),
                        RankUI.button("pluginless.rank.ui.title", player, () -> RankUI.command(player, "rank gui")),
                        RankUI.button("pluginless.cosmetic.screen", player, () -> RankUI.command(player, "cosmetics")),
                        RankUI.button("pluginless.auction.title", player, () -> RankUI.command(player, "ah")),
                        RankUI.button("pluginless.market.title", player, () -> RankUI.command(player, "market")),
                        RankUI.button("pluginless.stock.title", player, () -> RankUI.command(player, "stock")),
                        RankUI.button("pluginless.shares.title", player, () -> RankUI.command(player, "shares")),
                        RankUI.button("pluginless.company.title", player, () -> RankUI.command(player, "company")),
                        RankUI.button("pluginless.shop.title", player, () -> RankUI.command(player, "shops")),
                        RankUI.button("pluginless.taxes.screen", player, () -> RankUI.command(player, "taxes gui")),
                        RankUI.button("pluginless.qol.title", player, () -> RankUI.command(player, "qol")));
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    public static ModularUI createBalance(Player player) {
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                RankUI.label().bind(DataBindingBuilder.componentS2C(() -> balance(player)).build()),
                RankUI.button("pluginless.bank.title", player, () -> RankUI.command(player, "bank")));
        return RankUI.screen(player, "pluginless.ui.balance", content);
    }

    private static Component balance(Player player) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        return Component.translatable("pluginless.balance", Config.format(Ledger.of(server).balance(player.getUUID())));
    }
}
