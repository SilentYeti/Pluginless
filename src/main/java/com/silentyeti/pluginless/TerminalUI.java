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
                        new Label().bind(DataBindingBuilder.componentS2C(() -> balance(player)).build()));
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    private static Component balance(Player player) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        return Component.translatable("pluginless.balance", Config.format(Ledger.of(server).balance(player.getUUID())));
    }
}
