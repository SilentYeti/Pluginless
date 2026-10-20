package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class FeatureCommands {
    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        income(dispatcher);
        market(dispatcher);
        companies(dispatcher);
        stocks(dispatcher);
        dispatcher.register(literal("margin").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> Margin.summary(ctx.getSource().getServer(), player.getUUID()), false); return 1;
                })
                .then(literal("borrow").then(argument("amount", Commands.amount()).executes(ctx -> action(ctx, player -> Margin.borrow(player, Commands.cents(ctx)), "margin"))))
                .then(literal("repay").then(argument("amount", Commands.amount()).executes(ctx -> action(ctx,
                        player -> Margin.repay(ctx.getSource().getServer(), player.getUUID(), Commands.cents(ctx)), "margin")))));
        dispatcher.register(literal("short").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> Shorts.summary(ctx.getSource().getServer(), player.getUUID()), false); return 1;
                })
                .then(literal("open").then(ticker().then(argument("shares", LongArgumentType.longArg(1, OrderBook.MAX_QUANTITY)).executes(ctx -> action(ctx,
                        player -> Shorts.open(player, StringArgumentType.getString(ctx, "ticker"), LongArgumentType.getLong(ctx, "shares")), "short")))))
                .then(literal("close").then(ticker().executes(ctx -> action(ctx, player -> Shorts.close(player, StringArgumentType.getString(ctx, "ticker")), "short")))));
        shares(dispatcher);
        dispatcher.register(literal("bank").executes(ctx -> {
                    PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.BANK_UI); return 1;
                })
                .then(literal("balance").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> BankUI.summary(player), false); return 1;
                }))
                .then(literal("history").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> Bank.history(ctx.getSource().getServer(), player.getUUID()), false); return 1;
                }))
                .then(literal("request").then(Commands.player().then(argument("amount", Commands.amount()).executes(ctx -> {
                    var target = Commands.one(ctx);
                    return action(ctx, player -> Bank.request(player, target, Commands.cents(ctx)), "bank");
                }))))
                .then(literal("accept").executes(ctx -> action(ctx, player -> Bank.respond(player, true), "bank")))
                .then(literal("decline").executes(ctx -> action(ctx, player -> Bank.respond(player, false), "bank")))
                .then(literal("borrow").then(argument("amount", Commands.amount()).executes(ctx -> action(ctx, player -> Bank.borrow(player, Commands.cents(ctx)), "bank"))))
                .then(literal("repay").then(argument("amount", Commands.amount()).executes(ctx -> action(ctx,
                        player -> Bank.repay(ctx.getSource().getServer(), player.getUUID(), Commands.cents(ctx)), "bank")))));

        dispatcher.register(literal("qol").executes(ctx -> {
            PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.QOL_UI); return 1;
        }));
        dispatcher.register(literal("spawn").executes(ctx -> action(ctx, player -> Teleports.go(player, "spawn", Teleports.spawn(ctx.getSource().getServer())), "teleport")));
        dispatcher.register(literal("setspawn").requires(Commands.operator()).executes(ctx -> action(ctx, Teleports::setSpawn, "teleport")));
        dispatcher.register(literal("rtp").executes(ctx -> action(ctx, Teleports::rtp, "teleport")));
        dispatcher.register(literal("back").executes(ctx -> action(ctx, Teleports::back, "teleport")));
        for (String command : new String[]{"home", "sethome", "delhome"}) {
            dispatcher.register(literal(command).executes(ctx -> home(ctx, command, "home"))
                    .then(argument("name", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Teleports.homesOf(ctx.getSource().getServer(), ctx.getSource().getPlayerOrException().getUUID()).keySet(), builder))
                            .executes(ctx -> home(ctx, command, StringArgumentType.getString(ctx, "name")))));
        }
        dispatcher.register(literal("homes").executes(ctx -> {
            var player = ctx.getSource().getPlayerOrException();
            if (!Teleports.fee(player, "homes")) return failure(ctx, "teleport");
            ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.home.list", String.join(", ", Teleports.homesOf(ctx.getSource().getServer(), player.getUUID()).keySet())), false); return 1;
        }));
        for (String command : new String[]{"tpa", "tpahere"}) {
            dispatcher.register(literal(command).then(Commands.player().executes(ctx -> {
                var target = Commands.one(ctx);
                var online = ctx.getSource().getServer().getPlayerList().getPlayer(target.id());
                return action(ctx, player -> online != null && Teleports.request(player, online, command.equals("tpahere")), "teleport");
            })));
        }
        dispatcher.register(literal("tpaccept").executes(ctx -> action(ctx, player -> Teleports.respond(player, true), "teleport")));
        dispatcher.register(literal("tpdeny").executes(ctx -> action(ctx, player -> Teleports.respond(player, false), "teleport")));
        dispatcher.register(literal("warp").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.warp.list", String.join(", ", Teleports.warps(ctx.getSource().getServer()))), false); return 1;
                }).then(argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Teleports.warps(ctx.getSource().getServer()), builder))
                        .executes(ctx -> action(ctx, player -> Teleports.go(player, "warp", Teleports.destinations(ctx.getSource().getServer()).value.get("warp." + StringArgumentType.getString(ctx, "name"))), "teleport"))));
        dispatcher.register(literal("setwarp").requires(Commands.operator()).then(argument("name", StringArgumentType.word())
                .executes(ctx -> action(ctx, player -> Teleports.setWarp(player, StringArgumentType.getString(ctx, "name")), "teleport"))));
        dispatcher.register(literal("delwarp").requires(Commands.operator()).then(argument("name", StringArgumentType.word())
                .executes(ctx -> action(ctx, player -> Teleports.delWarp(player, StringArgumentType.getString(ctx, "name")), "teleport"))));

        dispatcher.register(literal("cosmetics").executes(ctx -> {
            PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.COSMETICS_UI); return 1;
        }));
        dispatcher.register(literal("nick").then(argument("name", StringArgumentType.word())
                .executes(ctx -> action(ctx, player -> Cosmetics.nick(player, StringArgumentType.getString(ctx, "name")), "cosmetic"))));
        dispatcher.register(literal("realname").then(argument("nick", StringArgumentType.word()).executes(ctx -> {
            var names = Cosmetics.realnames(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "nick"));
            ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.cosmetic.realname", String.join(", ", names)), false); return names.isEmpty() ? 0 : 1;
        })));
        dispatcher.register(literal("namecolor").then(argument("color", StringArgumentType.greedyString()).executes(ctx -> {
            String color = StringArgumentType.getString(ctx, "color").trim();
            try {
                String setting = color.equals("off") ? "off" : CosmeticNames.color(color);
                return action(ctx, player -> Cosmetics.color(player, setting), "cosmetic");
            } catch (IllegalArgumentException e) { return failure(ctx, "cosmetic"); }
        })));
        dispatcher.register(literal("namegradient").then(argument("colors", StringArgumentType.greedyString()).executes(ctx -> {
            String input = StringArgumentType.getString(ctx, "colors").trim();
            if (input.equals("off")) return action(ctx, player -> Cosmetics.color(player, "off"), "cosmetic");
            var colors = input.split("\\s+");
            if (colors.length != 2) return failure(ctx, "cosmetic");
            try {
                String setting = CosmeticNames.gradient(colors[0], colors[1]);
                return action(ctx, player -> Cosmetics.color(player, setting), "cosmetic");
            } catch (IllegalArgumentException e) { return failure(ctx, "cosmetic"); }
        })));
        dispatcher.register(literal("skull").then(Commands.player().executes(ctx -> {
            var target = Commands.one(ctx);
            return action(ctx, player -> Cosmetics.skull(player, target), "cosmetic");
        })));
        dispatcher.register(literal("hat").executes(ctx -> action(ctx, Cosmetics::hat, "cosmetic")));
        dispatcher.register(literal("nametitle").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.cosmetic.titles", String.join(", ", Features.settings.titles.keySet())), false); return 1;
                }).then(argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Features.settings.titles.keySet(), builder))
                        .executes(ctx -> action(ctx, player -> Cosmetics.title(player, StringArgumentType.getString(ctx, "name")), "cosmetic"))));
    }

    private static void income(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (boolean on : new boolean[]{true, false}) {
            dispatcher.register(literal("cosmetic").requires(Commands.operator()).then(literal(on ? "grant" : "revoke").then(Commands.player()
                    .then(argument("cosmetic", StringArgumentType.word()).executes(ctx -> {
                        var target = Commands.one(ctx);
                        String cosmetic = StringArgumentType.getString(ctx, "cosmetic");
                        if (!Cosmetics.grant(ctx.getSource().getServer(), target.id(), cosmetic, on)) return failure(ctx, "cosmetic");
                        Pluginless.LOGGER.info("cosmetic {} {} {} by {}", on ? "grant" : "revoke", target.name(), cosmetic, ctx.getSource().getTextName());
                        ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.feature.done"), true); return 1;
                    })))));
        }
        dispatcher.register(literal("sell").executes(ctx -> action(ctx, Income::sell, "sell")));
        dispatcher.register(literal("worth").executes(ctx -> {
            long cents = Income.worth(ctx.getSource().getPlayerOrException().getMainHandItem());
            ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.worth", Config.format(cents)), false); return cents > 0 ? 1 : 0;
        }));
        dispatcher.register(literal("taxes").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> Income.taxes(ctx.getSource().getServer(), player.getUUID()), false); return 1;
                })
                .then(literal("gui").executes(ctx -> { PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.TAXES_UI); return 1; })));

        dispatcher.register(literal("ah").executes(ctx -> { PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.AUCTION_UI); return 1; })
                .then(literal("list").executes(ctx -> listings(ctx, ""))
                        .then(argument("search", StringArgumentType.greedyString()).executes(ctx -> listings(ctx, StringArgumentType.getString(ctx, "search")))))
                .then(literal("sell").then(argument("amount", Commands.amount()).executes(ctx -> action(ctx, player -> Auctions.sell(player, Commands.cents(ctx)), "auction"))))
                .then(literal("auction").then(argument("amount", Commands.amount()).executes(ctx -> action(ctx, player -> Auctions.sell(player, Commands.cents(ctx), true), "auction"))))
                .then(literal("bid").then(argument("id", LongArgumentType.longArg(1)).then(argument("amount", Commands.amount()).executes(ctx -> {
                    String problem = Auctions.bid(ctx.getSource().getPlayerOrException(), LongArgumentType.getLong(ctx, "id"), Commands.cents(ctx));
                    if (problem != null) { ctx.getSource().sendFailure(Component.translatable("pluginless.auction." + problem)); return 0; }
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.auction.bid"), false); return 1;
                }))))
                .then(literal("buy").then(argument("id", LongArgumentType.longArg(1)).executes(ctx -> {
                    String problem = Auctions.buy(ctx.getSource().getPlayerOrException(), LongArgumentType.getLong(ctx, "id"));
                    if (problem != null) { ctx.getSource().sendFailure(Component.translatable("pluginless.auction." + problem)); return 0; }
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.auction.bought"), false); return 1;
                })))
                .then(literal("cancel").then(argument("id", LongArgumentType.longArg(1))
                        .executes(ctx -> action(ctx, player -> Auctions.cancel(player, LongArgumentType.getLong(ctx, "id"), false), "auction"))))
                .then(literal("view").then(argument("id", LongArgumentType.longArg(1)).executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Auctions.view(ctx.getSource().getServer(), LongArgumentType.getLong(ctx, "id")), false); return 1;
                })))
                .then(literal("collect").executes(ctx -> {
                    int moved = Auctions.collect(ctx.getSource().getPlayerOrException());
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.auction.collected", moved), false); return moved;
                }))
                .then(literal("watch").then(argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> action(ctx, player -> Auctions.watch(player, StringArgumentType.getString(ctx, "item"), true), "auction"))))
                .then(literal("unwatch").then(argument("item", StringArgumentType.greedyString())
                        .executes(ctx -> action(ctx, player -> Auctions.watch(player, StringArgumentType.getString(ctx, "item"), false), "auction"))))
                .then(literal("remove").requires(Commands.operator()).then(argument("id", LongArgumentType.longArg(1))
                        .executes(ctx -> action(ctx, player -> Auctions.cancel(player, LongArgumentType.getLong(ctx, "id"), true), "auction"))))
                .then(literal("history").requires(Commands.operator()).then(Commands.player().executes(ctx -> {
                    var target = Commands.one(ctx);
                    ctx.getSource().sendSuccess(() -> Auctions.sales(ctx.getSource().getServer(), target.id()), false); return 1;
                }))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> ticker() {
        return argument("ticker", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(StockExchange.config.stocks.keySet(), builder));
    }

    private static int stockAdmin(CommandContext<CommandSourceStack> ctx, String action, double value, long duration) {
        if (!StockExchange.admin(ctx.getSource().getServer(), ctx.getSource().getTextName(), StringArgumentType.getString(ctx, "ticker"), action, value, duration)) return failure(ctx, "stock");
        ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.feature.done"), false); return 1;
    }

    private static void stocks(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = literal("stock").executes(ctx -> { PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.STOCK_UI); return 1; })
                .then(literal("list").executes(ctx -> { ctx.getSource().sendSuccess(() -> StockExchange.list(ctx.getSource().getServer()), false); return 1; }))
                .then(literal("info").then(ticker().executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> StockExchange.info(ctx.getSource().getServer(), player.getUUID(), StringArgumentType.getString(ctx, "ticker")), false); return 1;
                })))
                .then(literal("portfolio").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> StockExchange.portfolio(ctx.getSource().getServer(), player.getUUID()), false); return 1;
                }))
                .then(literal("reload").requires(Commands.operator()).executes(ctx -> {
                    var problem = StockExchange.load();
                    if (problem != null) { ctx.getSource().sendFailure(Component.translatable("pluginless.rank.reload.failed", problem)); return 0; }
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.feature.done"), true); return 1;
                }))
                .then(literal("nudge").requires(Commands.operator()).then(ticker().then(argument("percent", DoubleArgumentType.doubleArg(-99, 1000))
                        .executes(ctx -> stockAdmin(ctx, "nudge", DoubleArgumentType.getDouble(ctx, "percent"), 0))
                        .then(argument("duration", StringArgumentType.word()).executes(ctx -> stockTimed(ctx, "nudge", DoubleArgumentType.getDouble(ctx, "percent")))))))
                .then(literal("trend").requires(Commands.operator()).then(ticker().then(literal("up").then(argument("strength", DoubleArgumentType.doubleArg(0, 1000))
                        .then(argument("duration", StringArgumentType.word()).executes(ctx -> stockTimed(ctx, "trend", DoubleArgumentType.getDouble(ctx, "strength"))))))
                        .then(literal("down").then(argument("strength", DoubleArgumentType.doubleArg(0, 1000))
                                .then(argument("duration", StringArgumentType.word()).executes(ctx -> stockTimed(ctx, "trend", -DoubleArgumentType.getDouble(ctx, "strength"))))))))
                .then(literal("shock").requires(Commands.operator()).then(ticker().then(argument("percent", DoubleArgumentType.doubleArg(-99, 1000))
                        .executes(ctx -> stockAdmin(ctx, "shock", DoubleArgumentType.getDouble(ctx, "percent"), 0)))))
                .then(literal("set").requires(Commands.operator()).then(ticker().then(argument("price", DoubleArgumentType.doubleArg(0, 1e9))
                        .executes(ctx -> stockAdmin(ctx, "set", DoubleArgumentType.getDouble(ctx, "price"), 0)))))
                .then(literal("freeze").requires(Commands.operator()).then(ticker().executes(ctx -> stockAdmin(ctx, "freeze", 0, 0))))
                .then(literal("unfreeze").requires(Commands.operator()).then(ticker().executes(ctx -> stockAdmin(ctx, "unfreeze", 0, 0))));
        for (boolean buy : new boolean[]{true, false}) {
            root.then(literal(buy ? "buy" : "sell").then(ticker().then(argument("shares", LongArgumentType.longArg(1, OrderBook.MAX_QUANTITY)).executes(ctx -> action(ctx,
                    player -> StockExchange.trade(player, StringArgumentType.getString(ctx, "ticker"), LongArgumentType.getLong(ctx, "shares"), buy), "stock")))));
        }
        dispatcher.register(root);
    }

    private static int stockTimed(CommandContext<CommandSourceStack> ctx, String action, double value) {
        try { return stockAdmin(ctx, action, value, Durations.millis(StringArgumentType.getString(ctx, "duration"))); }
        catch (IllegalArgumentException e) { return failure(ctx, "stock"); }
    }

    private static void shares(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = literal("shares").executes(ctx -> { PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.SHARES_UI); return 1; })
                .then(literal("portfolio").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> Shares.portfolio(ctx.getSource().getServer(), player.getUUID()), false); return 1;
                }))
                .then(literal("issue").then(argument("company", StringArgumentType.word()).then(argument("count", LongArgumentType.longArg(1, OrderBook.MAX_QUANTITY))
                        .then(argument("keep", LongArgumentType.longArg(0, OrderBook.MAX_QUANTITY)).then(argument("amount", Commands.amount()).executes(ctx -> action(ctx, player ->
                                Shares.issue(player, company(ctx), LongArgumentType.getLong(ctx, "count"), LongArgumentType.getLong(ctx, "keep"), Commands.cents(ctx)), "shares")))))))
                .then(literal("cancel").then(argument("id", LongArgumentType.longArg(1)).executes(ctx -> action(ctx, player -> Shares.cancel(player, LongArgumentType.getLong(ctx, "id")), "shares"))))
                .then(literal("dividend").then(argument("company", StringArgumentType.word()).then(argument("amount", Commands.amount())
                        .executes(ctx -> action(ctx, player -> Shares.dividend(player, company(ctx), Commands.cents(ctx)), "shares")))))
                .then(literal("takeover").then(argument("company", StringArgumentType.word()).executes(ctx -> action(ctx, player -> Shares.takeover(player, company(ctx)), "shares"))));
        for (boolean buy : new boolean[]{true, false}) {
            root.then(literal(buy ? "buy" : "sell").then(argument("company", StringArgumentType.word()).then(argument("quantity", LongArgumentType.longArg(1, OrderBook.MAX_QUANTITY))
                    .then(argument("amount", Commands.amount()).executes(ctx -> action(ctx,
                            player -> Shares.order(player, company(ctx), buy, Commands.cents(ctx), LongArgumentType.getLong(ctx, "quantity")), "shares"))))));
        }
        dispatcher.register(root);
    }

    private static void companies(CommandDispatcher<CommandSourceStack> dispatcher) {
        var name = argument("company", StringArgumentType.word()).suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                Companies.store(ctx.getSource().getServer()).value.companies.values().stream().map(company -> company.name), builder));
        dispatcher.register(literal("company").executes(ctx -> { PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.COMPANY_UI); return 1; })
                .then(literal("list").executes(ctx -> { ctx.getSource().sendSuccess(() -> Companies.list(ctx.getSource().getServer()), false); return 1; }))
                .then(literal("create").then(argument("company", StringArgumentType.word()).executes(ctx -> action(ctx, player -> Companies.create(player, company(ctx)), "company"))))
                .then(literal("info").then(name.executes(ctx -> { ctx.getSource().sendSuccess(() -> Companies.info(ctx.getSource().getServer(), company(ctx)), false); return 1; })))
                .then(literal("describe").then(argument("company", StringArgumentType.word()).then(argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> action(ctx, player -> Companies.describe(player, company(ctx), StringArgumentType.getString(ctx, "text")), "company")))))
                .then(literal("deposit").then(argument("company", StringArgumentType.word()).then(argument("amount", Commands.amount()).executes(ctx -> action(ctx, player ->
                        Companies.with(player, company(ctx), (book, company) -> book.deposit(player.getUUID(), company, Commands.cents(ctx), money(player, company, "deposit"))), "company")))))
                .then(literal("withdraw").then(argument("company", StringArgumentType.word()).then(argument("amount", Commands.amount()).executes(ctx -> action(ctx, player ->
                        Companies.with(player, company(ctx), (book, company) -> !Shares.locked(ctx.getSource().getServer(), company) && book.withdraw(player.getUUID(), company, Commands.cents(ctx), money(player, company, "withdraw"))), "company")))))
                .then(literal("hire").then(argument("company", StringArgumentType.word()).then(Commands.player().then(argument("role", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[]{"employee", "manager"}, builder)).executes(ctx -> {
                            var target = Commands.one(ctx);
                            return action(ctx, player -> Companies.with(player, company(ctx), (book, company) -> book.hire(player.getUUID(), company, target.id(), StringArgumentType.getString(ctx, "role"))), "company");
                        })))))
                .then(literal("fire").then(argument("company", StringArgumentType.word()).then(Commands.player().executes(ctx -> {
                    var target = Commands.one(ctx);
                    return action(ctx, player -> Companies.with(player, company(ctx), (book, company) -> book.fire(player.getUUID(), company, target.id())), "company");
                }))))
                .then(literal("leave").then(argument("company", StringArgumentType.word()).executes(ctx -> action(ctx, player ->
                        Companies.with(player, company(ctx), (book, company) -> book.fire(player.getUUID(), company, player.getUUID())), "company"))))
                .then(literal("wage").then(argument("company", StringArgumentType.word()).then(Commands.player().then(argument("wage", DoubleArgumentType.doubleArg(0, 1e10)).executes(ctx -> {
                    var target = Commands.one(ctx);
                    return action(ctx, player -> Companies.with(player, company(ctx), (book, company) -> book.wage(player.getUUID(), company, target.id(), price(ctx, "wage"))), "company");
                })))))
                .then(literal("dissolve").then(argument("company", StringArgumentType.word()).executes(ctx -> action(ctx, player -> Companies.dissolve(player, company(ctx)), "company")))));

        dispatcher.register(literal("shops").executes(ctx -> { PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.SHOP_UI); return 1; })
                .then(argument("search", StringArgumentType.greedyString()).executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Companies.directory(ctx.getSource().getServer(), StringArgumentType.getString(ctx, "search")), false); return 1;
                })));
        var root = literal("shop")
                .then(argument("shop", StringArgumentType.word()).executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    Companies.SELECTED.put(player.getUUID(), shopName(ctx));
                    PlayerUIMenuType.openUI(player, Pluginless.SHOP_UI); return 1;
                }))
                .then(literal("open").then(argument("company", StringArgumentType.word()).then(argument("shop", StringArgumentType.word())
                        .executes(ctx -> action(ctx, player -> Companies.open(player, company(ctx), shopName(ctx)), "shop")))))
                .then(literal("admin").requires(Commands.operator()).then(argument("shop", StringArgumentType.word())
                        .executes(ctx -> action(ctx, player -> Companies.open(player, null, shopName(ctx)), "shop"))))
                .then(literal("close").then(argument("shop", StringArgumentType.word()).executes(ctx -> action(ctx, player -> Companies.close(player, shopName(ctx)), "shop"))))
                .then(literal("describe").then(argument("shop", StringArgumentType.word()).then(argument("text", StringArgumentType.greedyString()).executes(ctx -> action(ctx, player ->
                        StringArgumentType.getString(ctx, "text").length() <= 128 && Companies.shop(player, shopName(ctx), "shops", (book, shop) -> {
                            shop.description = StringArgumentType.getString(ctx, "text"); return true;
                        }), "shop")))))
                .then(literal("icon").then(argument("shop", StringArgumentType.word()).then(item().executes(ctx -> action(ctx, player ->
                        Companies.shop(player, shopName(ctx), "shops", (book, shop) -> { shop.icon = item(ctx); return true; }), "shop")))))
                .then(literal("offer").then(argument("shop", StringArgumentType.word()).then(item()
                        .then(argument("sell", DoubleArgumentType.doubleArg(0, 1e10)).then(argument("buy", DoubleArgumentType.doubleArg(0, 1e10))
                                .then(argument("limit", LongArgumentType.longArg(0, OrderBook.MAX_QUANTITY)).executes(ctx -> action(ctx, player ->
                                        Companies.shop(player, shopName(ctx), "shops", (book, shop) -> book.offer(shop, item(ctx), price(ctx, "sell"), price(ctx, "buy"), LongArgumentType.getLong(ctx, "limit"))), "shop"))))))));
        for (String verb : new String[]{"stock", "unstock", "buy", "sell"}) {
            root.then(literal(verb).then(argument("shop", StringArgumentType.word()).then(item().then(argument("quantity", LongArgumentType.longArg(1, OrderBook.MAX_QUANTITY))
                    .executes(ctx -> action(ctx, player -> {
                        long quantity = LongArgumentType.getLong(ctx, "quantity");
                        return switch (verb) {
                            case "buy" -> Companies.buy(player, shopName(ctx), item(ctx), quantity);
                            case "sell" -> Companies.sell(player, shopName(ctx), item(ctx), quantity);
                            default -> Companies.stock(player, shopName(ctx), item(ctx), quantity, verb.equals("stock"));
                        };
                    }, "shop"))))));
        }
        dispatcher.register(root);
    }

    private static String company(CommandContext<CommandSourceStack> ctx) { return StringArgumentType.getString(ctx, "company"); }
    private static String shopName(CommandContext<CommandSourceStack> ctx) { return StringArgumentType.getString(ctx, "shop"); }
    private static long price(CommandContext<CommandSourceStack> ctx, String name) { return Math.round(DoubleArgumentType.getDouble(ctx, name) * 100); }
    private static Accounts money(ServerPlayer player, CompanyBook.Company company, String what) {
        return Ledger.of(player.level().getServer(), player.getUUID().toString(), "company." + what);
    }

    private static void market(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = literal("market").executes(ctx -> { PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.MARKET_UI); return 1; })
                .then(literal("book").then(item().executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Market.book(ctx.getSource().getServer(), item(ctx)), false); return 1;
                })))
                .then(literal("orders").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ctx.getSource().sendSuccess(() -> Market.mine(ctx.getSource().getServer(), player.getUUID()), false); return 1;
                }))
                .then(literal("cancel").then(argument("id", LongArgumentType.longArg(1))
                        .executes(ctx -> action(ctx, player -> Market.cancel(player, LongArgumentType.getLong(ctx, "id")), "market"))))
                .then(literal("collect").executes(ctx -> {
                    long moved = Market.collect(ctx.getSource().getPlayerOrException());
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.auction.collected", moved), false); return (int) Math.min(moved, Integer.MAX_VALUE);
                }));
        for (boolean buy : new boolean[]{true, false}) {
            root.then(literal(buy ? "buy" : "sell").then(item().then(argument("quantity", LongArgumentType.longArg(1, OrderBook.MAX_QUANTITY))
                    .then(argument("amount", Commands.amount()).executes(ctx -> action(ctx,
                            player -> Market.order(player, item(ctx), buy, Commands.cents(ctx), LongArgumentType.getLong(ctx, "quantity")), "market"))))));
            root.then(literal(buy ? "instabuy" : "instasell").then(item().then(argument("quantity", LongArgumentType.longArg(1, OrderBook.MAX_QUANTITY))
                    .executes(ctx -> action(ctx, player -> Market.instant(player, item(ctx), buy, LongArgumentType.getLong(ctx, "quantity")), "market")))));
        }
        dispatcher.register(root);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, net.minecraft.resources.ResourceLocation> item() {
        return argument("item", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Features.settings.marketItems, builder));
    }

    private static String item(CommandContext<CommandSourceStack> ctx) {
        return net.minecraft.commands.arguments.ResourceLocationArgument.getId(ctx, "item").toString();
    }

    private static int listings(CommandContext<CommandSourceStack> ctx, String search) {
        ctx.getSource().sendSuccess(() -> Auctions.list(ctx.getSource().getServer(), search, "all", "newest"), false); return 1;
    }

    private static int home(CommandContext<CommandSourceStack> ctx, String command, String name) throws CommandSyntaxException {
        return action(ctx, player -> switch (command) {
            case "sethome" -> Teleports.setHome(player, name);
            case "delhome" -> Teleports.delHome(player, name);
            default -> Teleports.go(player, "home", Teleports.homesOf(ctx.getSource().getServer(), player.getUUID()).get(name));
        }, "teleport");
    }

    private static int action(CommandContext<CommandSourceStack> ctx, Predicate<ServerPlayer> action, String feature) throws CommandSyntaxException {
        if (!action.test(ctx.getSource().getPlayerOrException())) return failure(ctx, feature);
        ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.feature.done"), false); return 1;
    }

    private static int failure(CommandContext<CommandSourceStack> ctx, String feature) {
        ctx.getSource().sendFailure(Component.translatable("pluginless." + feature + ".failed")); return 0;
    }
}
