package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.List;

import static com.silentyeti.pluginless.RankUI.*;

public final class CompanyUI {
    private static final class Form { String company = "", name = "", target = "", role = "employee", amount = "", shop = "", item = "", quantity = "1", sell = "0", buy = "0", limit = "0", search = ""; }

    public static ModularUI create(Player player) {
        var form = new Form();
        var server = player.level().getServer();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty() : Companies.list(server)).build()),
                field("pluginless.company.ui.name", () -> form.name, v -> form.name = v),
                button("pluginless.company.ui.create", player, () -> command(player, "company create " + word(form.name))),
                picker("pluginless.company.ui.company", player, () -> form.company, v -> form.company = v, () -> server == null ? List.of() : Companies.of(server, player.getUUID())),
                label().bind(DataBindingBuilder.componentS2C(() -> server == null || form.company.isEmpty() ? Component.empty() : Companies.info(server, form.company)).build()),
                field("pluginless.bank.amount", () -> form.amount, v -> form.amount = v),
                button("pluginless.company.ui.deposit", player, () -> command(player, "company deposit " + word(form.company) + " " + word(form.amount))),
                button("pluginless.company.ui.withdraw", player, () -> command(player, "company withdraw " + word(form.company) + " " + word(form.amount))),
                picker("pluginless.bank.player", player, () -> form.target, v -> form.target = v, () -> BankUI.players(player)),
                picker("pluginless.company.ui.role", player, () -> form.role, v -> form.role = v, () -> List.of("employee", "manager")),
                button("pluginless.company.ui.hire", player, () -> command(player, "company hire " + word(form.company) + " " + word(form.target) + " " + word(form.role))),
                button("pluginless.company.ui.fire", player, () -> command(player, "company fire " + word(form.company) + " " + word(form.target))),
                button("pluginless.company.ui.wage", player, () -> command(player, "company wage " + word(form.company) + " " + word(form.target) + " " + word(form.amount))),
                button("pluginless.company.ui.shop", player, () -> command(player, "shop open " + word(form.company) + " " + word(form.name))),
                button("pluginless.company.ui.dissolve", player, () -> command(player, "company dissolve " + word(form.company))),
                button("pluginless.shop.title", player, () -> command(player, "shops")));
        return screen(player, "pluginless.company.title", content);
    }

    public static ModularUI shops(Player player) {
        var form = new Form();
        var server = player.level().getServer();
        form.shop = Companies.SELECTED.getOrDefault(player.getUUID(), "");
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                field("pluginless.auction.ui.search", () -> form.search, v -> form.search = v),
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty() : Companies.directory(server, form.search.length() > 64 ? "" : form.search)).build()),
                picker("pluginless.shop.ui.shop", player, () -> form.shop, v -> form.shop = v,
                        () -> server == null ? List.of() : Companies.store(server).value.shops.values().stream().map(shop -> shop.name).toList()),
                label().bind(DataBindingBuilder.componentS2C(() -> server == null || form.shop.isEmpty() ? Component.empty() : Companies.page(server, form.shop)).build()),
                picker("pluginless.market.ui.item", player, () -> form.item, v -> form.item = v, () -> {
                    var shop = server == null ? null : Companies.store(server).value.shop(form.shop);
                    return shop == null ? List.of() : List.copyOf(shop.offers.keySet());
                }),
                field("pluginless.market.ui.quantity", () -> form.quantity, v -> form.quantity = v),
                button("pluginless.shop.ui.buy", player, () -> command(player, "shop buy " + word(form.shop) + " " + item(form) + " " + word(form.quantity))),
                button("pluginless.shop.ui.sell", player, () -> command(player, "shop sell " + word(form.shop) + " " + item(form) + " " + word(form.quantity))),
                field("pluginless.shop.ui.item", () -> form.item, v -> form.item = v),
                button("pluginless.shop.ui.stock", player, () -> command(player, "shop stock " + word(form.shop) + " " + item(form) + " " + word(form.quantity))),
                button("pluginless.shop.ui.unstock", player, () -> command(player, "shop unstock " + word(form.shop) + " " + item(form) + " " + word(form.quantity))),
                field("pluginless.shop.ui.sellprice", () -> form.sell, v -> form.sell = v),
                field("pluginless.shop.ui.buyprice", () -> form.buy, v -> form.buy = v),
                field("pluginless.shop.ui.limit", () -> form.limit, v -> form.limit = v),
                button("pluginless.shop.ui.offer", player, () -> command(player, "shop offer " + word(form.shop) + " " + item(form) + " " + word(form.sell) + " " + word(form.buy) + " " + word(form.limit))),
                button("pluginless.company.title", player, () -> command(player, "company")));
        return screen(player, "pluginless.shop.title", content);
    }

    public static ModularUI shares(Player player) {
        var form = new Form();
        var server = player.level().getServer();
        var content = new UIElement().layout(layout -> layout.width(280).gapAll(4)).addChildren(
                label().bind(DataBindingBuilder.componentS2C(() -> server == null ? Component.empty() : Shares.portfolio(server, player.getUUID())).build()),
                picker("pluginless.company.ui.company", player, () -> form.company, v -> form.company = v,
                        () -> server == null ? List.of() : Companies.store(server).value.companies.values().stream().map(company -> company.name).toList()),
                label().bind(DataBindingBuilder.componentS2C(() -> {
                    var company = server == null ? null : Companies.store(server).value.company(form.company);
                    return company == null ? Component.empty() : Shares.page(server, company);
                }).build()),
                field("pluginless.market.ui.quantity", () -> form.quantity, v -> form.quantity = v),
                field("pluginless.market.ui.price", () -> form.amount, v -> form.amount = v),
                button("pluginless.market.ui.buy", player, () -> command(player, "shares buy " + word(form.company) + " " + word(form.quantity) + " " + word(form.amount))),
                button("pluginless.market.ui.sell", player, () -> command(player, "shares sell " + word(form.company) + " " + word(form.quantity) + " " + word(form.amount))),
                field("pluginless.market.ui.id", () -> form.item, v -> form.item = v),
                button("pluginless.market.ui.cancel", player, () -> command(player, "shares cancel " + word(form.item))),
                button("pluginless.shares.ui.takeover", player, () -> command(player, "shares takeover " + word(form.company))),
                field("pluginless.shares.ui.keep", () -> form.limit, v -> form.limit = v),
                button("pluginless.shares.ui.issue", player, () -> command(player, "shares issue " + word(form.company) + " " + word(form.quantity) + " " + word(form.limit) + " " + word(form.amount))),
                button("pluginless.shares.ui.dividend", player, () -> command(player, "shares dividend " + word(form.company) + " " + word(form.amount))));
        return screen(player, "pluginless.shares.title", content);
    }

    // An item id is one token of a fixed shape, so a menu cannot append another command.
    private static String item(Form form) {
        return form.item.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") ? form.item : "?";
    }
}
