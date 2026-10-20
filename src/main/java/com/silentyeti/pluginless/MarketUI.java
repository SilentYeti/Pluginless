package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import static com.silentyeti.pluginless.RankUI.*;

public final class MarketUI {
    private static final class Form { String item = "", quantity = "1", price = "", id = ""; }

    public static ModularUI create(Player player) {
        var form = new Form();
        var server = player.level().getServer();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                picker("pluginless.market.ui.item", player, () -> form.item, v -> form.item = v, () -> Features.settings.marketItems),
                label().bind(DataBindingBuilder.componentS2C(() -> server == null || form.item.isEmpty() ? Component.empty() : Market.book(server, form.item)).build()),
                field("pluginless.market.ui.quantity", () -> form.quantity, v -> form.quantity = v),
                field("pluginless.market.ui.price", () -> form.price, v -> form.price = v),
                button("pluginless.market.ui.buy", player, () -> command(player, "market buy " + item(form) + " " + word(form.quantity) + " " + word(form.price))),
                button("pluginless.market.ui.sell", player, () -> command(player, "market sell " + item(form) + " " + word(form.quantity) + " " + word(form.price))),
                button("pluginless.market.ui.instabuy", player, () -> command(player, "market instabuy " + item(form) + " " + word(form.quantity))),
                button("pluginless.market.ui.instasell", player, () -> command(player, "market instasell " + item(form) + " " + word(form.quantity))),
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty() : Market.mine(server, player.getUUID())).build()),
                button("pluginless.market.ui.collect", player, () -> command(player, "market collect")),
                field("pluginless.market.ui.id", () -> form.id, v -> form.id = v),
                button("pluginless.market.ui.cancel", player, () -> command(player, "market cancel " + word(form.id))));
        return screen(player, "pluginless.market.title", content);
    }

    // The picker only offers configured ids; anything else a client sends becomes an invalid argument.
    private static String item(Form form) {
        return Features.settings.marketItems.contains(form.item) ? form.item : "?";
    }
}
