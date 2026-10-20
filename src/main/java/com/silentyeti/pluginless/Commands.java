package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.Command;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.commands.arguments.GameProfileArgument;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.function.Predicate;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class Commands {
    private static final SimpleCommandExceptionType ONE_PLAYER =
            new SimpleCommandExceptionType(Component.translatable("pluginless.one_player"));

    private static final DynamicCommandExceptionType BAD_PLAYER =
            new DynamicCommandExceptionType(name -> Component.translatable("pluginless.player.invalid", name));
    /** What /pluginless lists, in this order. Add every new root command here and a pluginless.help.<name> line to the lang file. */
    private static final String[] LISTED = {"balance", "pay", "baltop", "roth", "rank", "eco", "kit", "bank", "qol", "spawn", "setspawn", "home", "sethome", "delhome", "homes", "tpa", "tpahere", "tpaccept", "tpdeny", "rtp", "back", "warp", "setwarp", "delwarp", "cosmetics", "nick", "realname", "namecolor", "namegradient", "skull", "hat", "nametitle", "cosmetic", "sell", "worth", "taxes", "ah", "market", "company", "shops", "shop", "stock", "shares"};

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        FeatureCommands.register(dispatcher);
        dispatcher.register(literal("pluginless")
                .executes(ctx -> help(dispatcher, ctx.getSource()))
                .then(literal("help").executes(ctx -> help(dispatcher, ctx.getSource()))));

        for (var name : new String[]{"balance", "bal"}) {
            dispatcher.register(literal(name)
                    .executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        var cents = Ledger.of(ctx.getSource().getServer()).balance(player.getUUID());
                        ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.balance", Config.format(cents)), false);
                        return 1;
                    })
                    .then(literal("gui").executes(ctx -> {
                        PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.BALANCE_UI);
                        return 1;
                    }))
                    .then(player().executes(Commands::balanceOther))
                    .then(literal("@server").executes(Commands::balanceOther)));
        }

        dispatcher.register(literal("pay")
                .then(player().then(argument("amount", amount()).executes(Commands::pay)))
                .then(literal("@server").then(argument("amount", amount()).executes(Commands::pay))));

        dispatcher.register(literal("baltop").executes(ctx -> {
            var server = ctx.getSource().getServer();
            ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.baltop.header"), false);
            int rank = 1;
            for (var entry : Ledger.of(server).top(10)) {
                var line = "%d. %s  %s".formatted(rank++, name(server, entry.getKey()), Config.format(entry.getValue()));
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        }));

        var eco = literal("eco").requires(operator());
        for (var action : new String[]{"set", "add", "remove"}) {
            Command<CommandSourceStack> change = ctx -> {
                var target = one(ctx);
                var cents = cents(ctx);
                var accounts = Ledger.of(ctx.getSource().getServer(), ctx.getSource().getTextName(), "eco." + action);
                boolean ok = switch (action) {
                    case "set" -> accounts.set(target.id(), cents);
                    case "add" -> accounts.deposit(target.id(), cents);
                    default -> accounts.withdraw(target.id(), cents);
                };
                if (!ok) {
                    ctx.getSource().sendFailure(Component.translatable("pluginless.eco.failed", target.name()));
                    return 0;
                }
                Pluginless.LOGGER.info("eco {} {} {} by {}", action, target.name(), Config.format(cents), ctx.getSource().getTextName());
                ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.eco.done", target.name(),
                        Config.format(accounts.balance(target.id()))), true);
                return 1;
            };
            eco.then(literal(action)
                    .then(player().then(argument("amount", amount()).executes(change)))
                    .then(literal("@server").then(argument("amount", amount()).executes(change))));
        }
        dispatcher.register(eco);

        dispatcher.register(literal("rank")
                .executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    var rank = RankGate.rankOf(ctx.getSource().getServer(), player.getUUID());
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.rank.own", rank), false);
                    return 1;
                })
                .then(literal("rewards").executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    String rank = RankGate.rankOf(ctx.getSource().getServer(), player.getUUID());
                    ctx.getSource().sendSuccess(() -> RankUI.rewards(player, rank), false);
                    return 1;
                }))
                .then(literal("list").executes(ctx -> {
                    var names = String.join(", ", RankGate.ranks.ranks.keySet());
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.rank.list", names), false);
                    return 1;
                }))
                .then(literal("set").requires(operator())
                        .then(player()
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(RankGate.ranks.ranks.keySet(), builder))
                                        .executes(ctx -> setRank(ctx, false))
                                        .then(argument("duration", StringArgumentType.word()).executes(ctx -> setRank(ctx, true))))))
                .then(literal("gui").executes(ctx -> {
                    PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.RANK_UI);
                    return 1;
                }))
                .then(literal("promote").then(player().then(argument("track", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(RankGate.ranks.tracks.keySet(), builder))
                        .executes(ctx -> step(ctx, 1)))))
                .then(literal("demote").requires(operator()).then(player().then(argument("track", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(RankGate.ranks.tracks.keySet(), builder))
                        .executes(ctx -> step(ctx, -1)))))
                .then(literal("reload").requires(operator()).executes(ctx -> {
                    var problem = RankGate.load();
                    if (problem != null) {
                        ctx.getSource().sendFailure(Component.translatable("pluginless.rank.reload.failed", problem));
                        return 0;
                    }
                    ctx.getSource().getServer().getPlayerList().getPlayers().forEach(RankGate::refresh);
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.rank.reload.done"), true);
                    return 1;
                })));

        dispatcher.register(literal("kit")
                .executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    var names = RankGate.ranks.kits.keySet().stream()
                            .filter(name -> RankRewards.canKit(ctx.getSource().getServer(), player, name)).toList();
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.kit.list", String.join(", ", names)), false);
                    return 1;
                })
                .then(argument("kit", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(RankGate.ranks.kits.keySet(), builder))
                        .executes(ctx -> RankRewards.kit(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx, "kit"), System.currentTimeMillis()) ? 1 : 0)));

        dispatcher.register(literal("roth").executes(ctx -> {
            PlayerUIMenuType.openUI(ctx.getSource().getPlayerOrException(), Pluginless.TERMINAL_UI);
            return 1;
        }));
    }

    /** Like vanilla /help, but only our commands, and only the ones this sender may use. */
    private static int help(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("pluginless.help.header").withStyle(ChatFormatting.GOLD), false);
        int shown = 0;
        for (var name : LISTED) {
            var node = dispatcher.getRoot().getChild(name);
            if (node == null || !node.canUse(source)) continue;
            var usage = String.join(" | ", dispatcher.getSmartUsage(node, source).values());
            var line = Component.literal(("/" + name + " " + usage).trim()).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("pluginless.help." + name).withStyle(ChatFormatting.WHITE));
            source.sendSuccess(() -> line, false);
            shown++;
        }
        return shown;
    }

    static Predicate<CommandSourceStack> operator() {
        //? if >=26.1 {
        /*return net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS);
        *///?} else {
        return source -> source.hasPermission(2);
        //?}
    }

    static DoubleArgumentType amount() {
        return DoubleArgumentType.doubleArg(0, 1e15);
    }

    static long cents(CommandContext<CommandSourceStack> ctx) {
        return Math.round(DoubleArgumentType.getDouble(ctx, "amount") * 100);
    }

    private static int balanceOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var target = one(ctx);
        var cents = Ledger.of(ctx.getSource().getServer()).balance(target.id());
        ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.balance.other", target.name(), Config.format(cents)), false);
        return 1;
    }

    private static int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var sender = ctx.getSource().getPlayerOrException();
        var target = one(ctx);
        var cents = cents(ctx);
        var server = ctx.getSource().getServer();
        // The sender pays the tax on top; both movements happen or neither does.
        long tax = Taxes.percent(cents, Config.transferTax(), Income.perk(server, sender.getUUID(), "fee_multiplier"));
        var accounts = Ledger.of(server, sender.getUUID().toString(), "pay");
        boolean affordable = accounts.balance(sender.getUUID()) - tax >= cents && !sender.getUUID().equals(target.id());
        if (!affordable || tax > 0 && !Ledger.collect(server, sender.getUUID(), tax, "pay.tax")) {
            ctx.getSource().sendFailure(Component.translatable("pluginless.pay.failed"));
            return 0;
        }
        if (!accounts.transfer(sender.getUUID(), target.id(), cents)) {
            if (tax > 0) Ledger.of(server, "server", "pay.tax.refund").refund(sender.getUUID(), tax);
            ctx.getSource().sendFailure(Component.translatable("pluginless.pay.failed"));
            return 0;
        }
        Pluginless.LOGGER.info("pay {} -> {} {}", sender.getScoreboardName(), target.name(), Config.format(cents));
        ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.pay.sent", Config.format(cents), target.name()), false);
        var receiver = server.getPlayerList().getPlayer(target.id());
        if (receiver != null) receiver.sendSystemMessage(Component.translatable("pluginless.pay.received", Config.format(cents), sender.getDisplayName()));
        return 1;
    }

    static RequiredArgumentBuilder<CommandSourceStack, GameProfileArgument.Result> player() {
        return argument("player", GameProfileArgument.gameProfile());
    }

    record Target(UUID id, String name) {}

    static Target one(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        for (var node : ctx.getNodes()) {
            if (node.getNode().getName().equals("player")) {
                // Keep Minecraft's serialized argument type while resolving never-seen names ourselves.
                var range = node.getRange();
                return target(ctx.getSource(), ctx.getInput().substring(range.getStart(), range.getEnd()));
            }
            if (node.getNode().getName().equals("@server")) return new Target(Accounts.SERVER, "@server");
        }
        throw ONE_PLAYER.create();
    }

    static Target target(CommandSourceStack source, String name) throws CommandSyntaxException {
        if (name.equals("@server")) return new Target(Accounts.SERVER, "@server");
        if (name.startsWith("@")) {
            var players = new EntitySelectorParser(new StringReader(name), true).parse().findPlayers(source);
            if (players.size() != 1) throw ONE_PLAYER.create();
            var player = players.get(0);
            return new Target(player.getUUID(), player.getScoreboardName());
        }
        if (!name.matches("[a-zA-Z0-9_]{1,16}")) throw BAD_PLAYER.create(name);
        return new Target(Players.resolve(source.getServer(), name), name);
    }

    static String name(MinecraftServer server, UUID id) {
        if (Accounts.system(id)) {
            String company = id.equals(Accounts.ESCROW) ? "escrow" : Companies.accountName(server, id);
            if (company != null) return company;
        }
        String known = Players.name(server, id);
        if (!known.equals(id.toString())) return known;
        //? if >=26.1 {
        /*return server.services().nameToIdCache().get(id).map(profile -> profile.name()).orElse(id.toString());
        *///?} else {
        return server.getProfileCache().get(id).map(profile -> profile.getName()).orElse(id.toString());
        //?}
    }

    private static int setRank(CommandContext<CommandSourceStack> ctx, boolean temporary) throws CommandSyntaxException {
        var target = one(ctx);
        var rank = StringArgumentType.getString(ctx, "rank");
        if (target.id().equals(Accounts.SERVER) || !RankGate.ranks.ranks.containsKey(rank)) {
            ctx.getSource().sendFailure(Component.translatable("pluginless.rank.unknown", rank));
            return 0;
        }
        try {
            if (temporary) {
                long duration = Durations.millis(StringArgumentType.getString(ctx, "duration"));
                RankGate.setRank(ctx.getSource().getServer(), target.id(), rank, duration, System.currentTimeMillis());
            } else RankGate.setRank(ctx.getSource().getServer(), target.id(), rank);
        } catch (IllegalArgumentException | ArithmeticException e) {
            ctx.getSource().sendFailure(Component.translatable("pluginless.duration.invalid"));
            return 0;
        }
        Pluginless.LOGGER.info("rank {} -> {} by {}", target.name(), rank, ctx.getSource().getTextName());
        ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.rank.set", target.name(), rank), true);
        return 1;
    }

    private static int step(CommandContext<CommandSourceStack> ctx, int direction) throws CommandSyntaxException {
        var target = one(ctx);
        String track = StringArgumentType.getString(ctx, "track");
        var source = ctx.getSource();
        var server = source.getServer();
        String current = RankGate.rankOf(server, target.id());
        String next = RankGate.ranks.step(track, current, direction);
        if (next == null || target.id().equals(Accounts.SERVER)) {
            source.sendFailure(Component.translatable("pluginless.rank.track.failed"));
            return 0;
        }
        if (!operator().test(source)) {
            var player = source.getPlayerOrException();
            var price = RankGate.ranks.tracks.get(track).prices.get(next);
            if (direction < 0 || !player.getUUID().equals(target.id()) || price == null) {
                source.sendFailure(Component.translatable("pluginless.rank.track.denied"));
                return 0;
            }
            double amount = price * 100 * Math.max(0, RankGate.ranks.number(current, "fee_multiplier", 1));
            long cents = Math.round(amount);
            if (!Double.isFinite(amount) || amount >= Long.MAX_VALUE
                    || cents > 0 && !Ledger.collect(server, target.id(), cents, "rank.promote." + track)) {
                source.sendFailure(Component.translatable("pluginless.pay.failed"));
                return 0;
            }
        }
        RankGate.setRank(server, target.id(), next);
        source.sendSuccess(() -> Component.translatable("pluginless.rank.set", target.name(), next), true);
        return 1;
    }
}
