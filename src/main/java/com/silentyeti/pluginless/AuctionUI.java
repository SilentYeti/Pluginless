package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

import static com.silentyeti.pluginless.RankUI.*;

public final class AuctionUI {
    private static final class Form { String search = "", category = "all", sort = "newest", id = "", price = ""; Component status = Component.empty(); }

    public static ModularUI create(Player player) {
        var form = new Form();
        var server = player.level().getServer();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty() : Auctions.box(server, player.getUUID())).build()),
                button("pluginless.auction.ui.collect", player, () -> command(player, "ah collect")),
                field("pluginless.auction.ui.price", () -> form.price, v -> form.price = v),
                button("pluginless.auction.ui.sell", player, () -> command(player, "ah sell " + word(form.price))),
                button("pluginless.auction.ui.start", player, () -> command(player, "ah auction " + word(form.price))),
                field("pluginless.auction.ui.search", () -> form.search, v -> form.search = v),
                picker("pluginless.auction.ui.category", player, () -> form.category, v -> form.category = v, () -> {
                    var categories = new ArrayList<String>(); categories.add("all"); categories.addAll(Features.settings.auctionCategories.keySet()); return categories;
                }),
                picker("pluginless.auction.ui.sort", player, () -> form.sort, v -> form.sort = v, () -> List.of("newest", "oldest", "price", "expensive")),
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty()
                        : Auctions.list(server, form.search.length() > 64 ? "" : form.search, form.category, form.sort)).build()),
                field("pluginless.auction.ui.id", () -> form.id, v -> form.id = v),
                button("pluginless.auction.ui.view", player, () -> {
                    try { form.status = Auctions.view(server, Long.parseLong(form.id)); } catch (NumberFormatException e) { form.status = Component.translatable("pluginless.auction.gone"); }
                }),
                button("pluginless.auction.ui.buy", player, () -> command(player, "ah buy " + word(form.id))),
                button("pluginless.auction.ui.bid", player, () -> command(player, "ah bid " + word(form.id) + " " + word(form.price))),
                button("pluginless.auction.ui.cancel", player, () -> command(player, "ah cancel " + word(form.id))),
                label().bind(DataBindingBuilder.componentS2C(() -> form.status).build()));
        return screen(player, "pluginless.auction.title", content);
    }

    public static ModularUI taxes(Player player) {
        var server = player.level().getServer();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty() : Income.taxes(server, player.getUUID())).build()));
        return screen(player, "pluginless.taxes.screen", content);
    }
}
