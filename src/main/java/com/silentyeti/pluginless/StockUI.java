package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.List;

import static com.silentyeti.pluginless.RankUI.*;

public final class StockUI {
    private static final class Form { String ticker = "", shares = "1", value = "", time = "1h"; }

    /** One screen for everyone; the admin buttons run operator-only commands, so they do nothing for other players. */
    public static ModularUI create(Player player) {
        var form = new Form();
        var server = player.level().getServer();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty() : StockExchange.list(server)).build()),
                picker("pluginless.stock.ui.ticker", player, () -> form.ticker, v -> form.ticker = v, () -> List.copyOf(StockExchange.config.stocks.keySet())),
                label().bind(DataBindingBuilder.componentS2C(() -> server == null || form.ticker.isEmpty() ? Component.empty() : StockExchange.info(server, player.getUUID(), form.ticker)).build()),
                field("pluginless.stock.ui.shares", () -> form.shares, v -> form.shares = v),
                button("pluginless.stock.ui.buy", player, () -> command(player, "stock buy " + word(form.ticker) + " " + word(form.shares))),
                button("pluginless.stock.ui.sell", player, () -> command(player, "stock sell " + word(form.ticker) + " " + word(form.shares))),
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty() : StockExchange.portfolio(server, player.getUUID())).build()),
                label().setText(Component.translatable("pluginless.stock.ui.admin")),
                field("pluginless.stock.ui.value", () -> form.value, v -> form.value = v),
                field("pluginless.stock.ui.time", () -> form.time, v -> form.time = v),
                button("pluginless.stock.ui.nudge", player, () -> command(player, "stock nudge " + word(form.ticker) + " " + word(form.value) + " " + word(form.time))),
                button("pluginless.stock.ui.trend", player, () -> command(player, "stock trend " + word(form.ticker) + (form.value.startsWith("-") ? " down " + word(form.value.substring(1)) : " up " + word(form.value)) + " " + word(form.time))),
                button("pluginless.stock.ui.shock", player, () -> command(player, "stock shock " + word(form.ticker) + " " + word(form.value))),
                button("pluginless.stock.ui.set", player, () -> command(player, "stock set " + word(form.ticker) + " " + word(form.value))),
                button("pluginless.stock.ui.freeze", player, () -> command(player, "stock freeze " + word(form.ticker))),
                button("pluginless.stock.ui.unfreeze", player, () -> command(player, "stock unfreeze " + word(form.ticker))));
        return screen(player, "pluginless.stock.title", content);
    }
}
