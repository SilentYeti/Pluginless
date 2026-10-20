package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Companies and shops on a server: CompanyBook holds the rules, this adds items, messages and saving. */
final class Companies {
    private static final Codec<CompanyBook.Company> COMPANY = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("id").forGetter(company -> company.id),
            Codec.STRING.fieldOf("name").forGetter(company -> company.name),
            Codec.STRING.fieldOf("description").forGetter(company -> company.description),
            UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(company -> company.owner),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.STRING).fieldOf("members").forGetter(company -> company.members),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG).fieldOf("wages").forGetter(company -> company.wages)
    ).apply(instance, (id, name, description, owner, members, wages) -> {
        var company = new CompanyBook.Company();
        company.id = id; company.name = name; company.description = description; company.owner = owner;
        company.members.putAll(members); company.wages.putAll(wages);
        return company;
    }));
    private static final Codec<CompanyBook.Offer> OFFER = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("sellPrice").forGetter(CompanyBook.Offer::sellPrice),
            Codec.LONG.fieldOf("buyPrice").forGetter(CompanyBook.Offer::buyPrice),
            Codec.LONG.fieldOf("buyLimit").forGetter(CompanyBook.Offer::buyLimit)
    ).apply(instance, CompanyBook.Offer::new));
    private static final Codec<CompanyBook.Shop> SHOP = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.fieldOf("id").forGetter(shop -> shop.id),
            Codec.LONG.fieldOf("company").forGetter(shop -> shop.company),
            Codec.STRING.fieldOf("name").forGetter(shop -> shop.name),
            Codec.STRING.fieldOf("description").forGetter(shop -> shop.description),
            Codec.STRING.fieldOf("icon").forGetter(shop -> shop.icon),
            Codec.unboundedMap(Codec.STRING, OFFER).fieldOf("offers").forGetter(shop -> shop.offers),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("stock").forGetter(shop -> shop.stock)
    ).apply(instance, (id, company, name, description, icon, offers, stock) -> {
        var shop = new CompanyBook.Shop();
        shop.id = id; shop.company = company; shop.name = name; shop.description = description; shop.icon = icon;
        shop.offers.putAll(offers); shop.stock.putAll(stock);
        return shop;
    }));
    static final Codec<CompanyBook> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            COMPANY.listOf().fieldOf("companies").forGetter(book -> List.copyOf(book.companies.values())),
            SHOP.listOf().fieldOf("shops").forGetter(book -> List.copyOf(book.shops.values())),
            Codec.LONG.fieldOf("nextId").forGetter(book -> book.nextId),
            Codec.LONG.fieldOf("lastPayroll").forGetter(book -> book.lastPayroll)
    ).apply(instance, (companies, shops, nextId, lastPayroll) -> {
        var book = new CompanyBook();
        companies.forEach(company -> book.companies.put(company.id, company));
        shops.forEach(shop -> book.shops.put(shop.id, shop));
        book.nextId = nextId; book.lastPayroll = lastPayroll;
        return book;
    }));

    static Store<CompanyBook> store(MinecraftServer server) {
        return Store.open(server, "companies", CODEC, CompanyBook::new);
    }

    private static Accounts accounts(ServerPlayer player, String reason) {
        return Ledger.of(player.level().getServer(), player.getUUID().toString(), reason);
    }

    /** The name shown for a company account in histories; null when the id is not one. */
    static String accountName(MinecraftServer server, UUID id) {
        return store(server).value.companies.values().stream().filter(company -> CompanyBook.account(company.id).equals(id))
                .map(company -> "company:" + company.name).findFirst().orElse(null);
    }

    static boolean create(ServerPlayer player, String name) {
        var server = player.level().getServer();
        var id = player.getUUID();
        // A debtor must clear the debt before founding a company.
        if (server == null || Bank.debt(server, id) != 0) return false;
        long limit = Math.round(RankGate.ranks.number(RankGate.rankOf(server, id), "companies", Config.companies()));
        var store = store(server);
        if (store.value.create(id, name, Config.companyFeeCents(), limit, accounts(player, "company.create")) == null) return false;
        store.setDirty();
        return true;
    }

    /** Runs a change on a named company and saves when it succeeded. */
    static boolean with(ServerPlayer player, String name, java.util.function.BiPredicate<CompanyBook, CompanyBook.Company> change) {
        var store = store(player.level().getServer());
        var company = store.value.company(name);
        if (company == null || !change.test(store.value, company)) return false;
        store.setDirty();
        return true;
    }

    static boolean describe(ServerPlayer player, String name, String text) {
        return text.length() <= 128 && with(player, name, (book, company) -> {
            if (!company.can(player.getUUID(), "hire")) return false;
            company.description = text;
            return true;
        });
    }

    static boolean dissolve(ServerPlayer player, String name) {
        var server = player.level().getServer();
        return with(player, name, (book, company) -> {
            if (!player.getUUID().equals(company.owner)) return false;
            // With shares in players' hands the account belongs to the shareholders, not the owner alone.
            Shares.liquidate(server, company, "company.dissolve");
            var returned = book.dissolve(player.getUUID(), company, accounts(player, "company.dissolve"));
            if (returned == null) return false;
            returned.forEach((item, count) -> Market.give(server, player.getUUID(), item, count));
            return true;
        });
    }

    static void tick(MinecraftServer server, long now) {
        long interval = Config.payrollMillis();
        var store = store(server);
        if (interval == 0 || now - store.value.lastPayroll < interval) return;
        // The first run only starts the clock, so a new world does not pay on its first minute.
        if (store.value.lastPayroll != 0) {
            for (var company : store.value.payroll(Ledger.of(server, "server", "company.wage"))) {
                Pluginless.LOGGER.info("payroll: {} could not pay its wages", company.name);
                if (Config.bankruptcy()) Shares.bankrupt(server, company);
            }
        }
        store.value.lastPayroll = now;
        store.setDirty();
    }

    static long storage(MinecraftServer server, CompanyBook book, CompanyBook.Shop shop) {
        var company = book.companies.get(shop.company);
        return company == null ? Long.MAX_VALUE : Math.round(RankGate.ranks.number(RankGate.rankOf(server, company.owner), "shop_storage", Config.shopStorage()));
    }

    /** Runs a change on a named shop the player has the permission for, and saves when it succeeded. */
    static boolean shop(ServerPlayer player, String name, String permission, java.util.function.BiPredicate<CompanyBook, CompanyBook.Shop> change) {
        var store = store(player.level().getServer());
        var shop = store.value.shop(name);
        if (shop == null || permission != null && !store.value.can(player.getUUID(), shop, permission, RankGate.isOperator(player)) || !change.test(store.value, shop)) return false;
        store.setDirty();
        return true;
    }

    static boolean open(ServerPlayer player, String company, String name) {
        // The subcommand words of /shop cannot be shop names, or /shop <name> could not reach them.
        if (List.of("open", "close", "admin", "offer", "stock", "unstock", "buy", "sell", "describe", "icon").contains(name.toLowerCase(java.util.Locale.ROOT))) return false;
        if (company == null) {
            var store = store(player.level().getServer());
            if (!RankGate.isOperator(player) || store.value.open(player.getUUID(), null, name, 0) == null) return false;
            store.setDirty();
            return true;
        }
        return with(player, company, (book, owner) -> book.open(player.getUUID(), owner, name, Config.shopsPerCompany()) != null);
    }

    static boolean close(ServerPlayer player, String name) {
        var server = player.level().getServer();
        return shop(player, name, "shops", (book, shop) -> {
            shop.stock.forEach((item, count) -> Market.give(server, player.getUUID(), item, count));
            book.shops.remove(shop.id);
            return true;
        });
    }

    static boolean stock(ServerPlayer player, String name, String item, long quantity, boolean in) {
        var server = player.level().getServer();
        return shop(player, name, "stock", (book, shop) -> {
            if (shop.admin() || quantity <= 0) return false;
            if (in) {
                if (book.room(shop, storage(server, book, shop)) < quantity || !Market.takePlain(player, item, quantity)) return false;
                shop.stock.merge(item, quantity, Long::sum);
            } else {
                if (shop.stock.getOrDefault(item, 0L) < quantity) return false;
                if (shop.stock.merge(item, -quantity, Long::sum) == 0) shop.stock.remove(item);
                Market.give(server, player.getUUID(), item, quantity);
                Market.collect(player);
            }
            return true;
        });
    }

    static boolean buy(ServerPlayer player, String name, String item, long quantity) {
        var server = player.level().getServer();
        return shop(player, name, null, (book, shop) -> {
            var offer = shop.offers.get(item);
            var company = book.companies.get(shop.company);
            if (offer == null || quantity <= 0 || quantity > OrderBook.MAX_QUANTITY) return false;
            long tax = company == null ? 0 : Taxes.percent(offer.sellPrice() * quantity, Config.shopTax(), Income.perk(server, company.owner, "fee_multiplier"));
            if (!book.buy(player.getUUID(), shop, item, quantity, tax, accounts(player, "shop." + shop.name))) return false;
            // Bought items wait in the collection box when the inventory is full.
            Market.give(server, player.getUUID(), item, quantity);
            Market.collect(player);
            return true;
        });
    }

    static boolean sell(ServerPlayer player, String name, String item, long quantity) {
        var server = player.level().getServer();
        return shop(player, name, null, (book, shop) -> {
            long storage = storage(server, book, shop);
            var accounts = accounts(player, "shop." + shop.name);
            if (!book.wants(shop, item, quantity, storage, accounts) || !Market.takePlain(player, item, quantity)) return false;
            if (book.sell(player.getUUID(), shop, item, quantity, storage, accounts, pay -> Ledger.payout(server, player.getUUID(), pay, "shop." + shop.name))) return true;
            // The shop could not pay after all: the items go back through the collection box.
            Market.give(server, player.getUUID(), item, quantity);
            Market.collect(player);
            return false;
        });
    }

    static Component info(MinecraftServer server, String name) {
        var book = store(server).value;
        var company = book.company(name);
        if (company == null) return Component.translatable("pluginless.company.unknown");
        var members = new java.util.ArrayList<String>();
        company.members.forEach((id, role) -> members.add(Commands.name(server, id) + " (" + role
                + (company.wages.containsKey(id) ? ", " + Config.format(company.wages.get(id)) : "") + ")"));
        var shops = book.shops.values().stream().filter(shop -> shop.company == company.id).map(shop -> shop.name).toList();
        return Component.translatable("pluginless.company.info", company.name, company.description, Commands.name(server, company.owner),
                Config.format(Ledger.of(server).balance(CompanyBook.account(company.id))), String.join(", ", members), String.join(", ", shops))
                .append(Shares.page(server, company)).append(Bank.history(server, CompanyBook.account(company.id)));
    }

    static List<String> of(MinecraftServer server, UUID player) {
        return store(server).value.companies.values().stream().filter(company -> company.can(player, "stock")).map(company -> company.name).toList();
    }

    static Component list(MinecraftServer server) {
        return Component.translatable("pluginless.company.list", String.join(", ", store(server).value.companies.values().stream().map(company -> company.name).toList()));
    }

    static Component directory(MinecraftServer server, String search) {
        var book = store(server).value;
        var found = book.find(search);
        var result = Component.translatable("pluginless.shop.directory", found.size());
        // shortcut: the first 30 offers, add paging when the directory outgrows search
        for (var entry : found.subList(0, Math.min(30, found.size()))) result.append(line(entry.getKey(), entry.getValue().getKey(), entry.getValue().getValue(), true));
        return result;
    }

    static Component page(MinecraftServer server, String name) {
        var book = store(server).value;
        var shop = book.shop(name);
        if (shop == null) return Component.translatable("pluginless.shop.unknown");
        var company = book.companies.get(shop.company);
        var result = Component.translatable("pluginless.shop.page", shop.name, shop.icon, shop.description, company == null ? "server" : company.name);
        shop.offers.forEach((item, offer) -> result.append(line(shop, item, offer, false)));
        return result;
    }

    private static Component line(CompanyBook.Shop shop, String item, CompanyBook.Offer offer, boolean named) {
        return Component.translatable("pluginless.shop.line", named ? shop.name + ": " : "", item,
                offer.sellPrice() == 0 ? "-" : Config.format(offer.sellPrice()), offer.buyPrice() == 0 ? "-" : Config.format(offer.buyPrice()),
                shop.admin() ? "∞" : String.valueOf(shop.stock.getOrDefault(item, 0L)), shop.admin() ? "∞" : String.valueOf(offer.buyLimit()));
    }

    static final Map<UUID, String> SELECTED = new HashMap<>();
}
