package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import java.util.function.Predicate;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class Commands {
    private static final SimpleCommandExceptionType ONE_PLAYER =
            new SimpleCommandExceptionType(Component.translatable("pluginless.one_player"));

    private static final DynamicCommandExceptionType NEVER_PLAYED =
            new DynamicCommandExceptionType(name -> Component.translatable("pluginless.never_played", name));

    /** What /pluginless lists, in this order. Add every new root command here and a pluginless.help.<name> line to the lang file. */
    private static final String[] LISTED = {"balance", "pay", "baltop", "roth", "rank", "eco"};

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
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
                    .then(argument("player", GameProfileArgument.gameProfile()).executes(ctx -> {
                        var target = one(ctx);
                        var cents = Ledger.of(ctx.getSource().getServer()).balance(target.id());
                        ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.balance.other", target.name(), Config.format(cents)), false);
                        return 1;
                    })));
        }

        dispatcher.register(literal("pay")
                .then(argument("player", GameProfileArgument.gameProfile())
                        .then(argument("amount", amount()).executes(ctx -> {
                            var sender = ctx.getSource().getPlayerOrException();
                            var target = one(ctx);
                            var cents = cents(ctx);
                            var server = ctx.getSource().getServer();
                            if (!Ledger.of(server).transfer(sender.getUUID(), target.id(), cents)) {
                                ctx.getSource().sendFailure(Component.translatable("pluginless.pay.failed"));
                                return 0;
                            }
                            // shortcut: the server log is the transaction log, give it its own file or table when the bank lands
                            Pluginless.LOGGER.info("pay {} -> {} {}", sender.getScoreboardName(), target.name(), Config.format(cents));
                            ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.pay.sent", Config.format(cents), target.name()), false);
                            var receiver = server.getPlayerList().getPlayer(target.id());
                            if (receiver != null) {
                                receiver.sendSystemMessage(Component.translatable("pluginless.pay.received", Config.format(cents), sender.getDisplayName()));
                            }
                            return 1;
                        }))));

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
            eco.then(literal(action)
                    .then(argument("player", GameProfileArgument.gameProfile())
                            .then(argument("amount", amount()).executes(ctx -> {
                                var target = one(ctx);
                                var cents = cents(ctx);
                                var accounts = Ledger.of(ctx.getSource().getServer());
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
                            }))));
        }
        dispatcher.register(eco);

        dispatcher.register(literal("rank")
                .executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    var rank = RankGate.rankOf(ctx.getSource().getServer(), player.getUUID());
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.rank.own", rank), false);
                    return 1;
                })
                .then(literal("list").executes(ctx -> {
                    var names = String.join(", ", RankGate.ranks.ranks.keySet());
                    ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.rank.list", names), false);
                    return 1;
                }))
                .then(literal("set").requires(operator())
                        .then(argument("player", GameProfileArgument.gameProfile())
                                .then(argument("rank", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(RankGate.ranks.ranks.keySet(), builder))
                                        .executes(ctx -> {
                                            var target = one(ctx);
                                            var rank = StringArgumentType.getString(ctx, "rank");
                                            if (!RankGate.ranks.ranks.containsKey(rank)) {
                                                ctx.getSource().sendFailure(Component.translatable("pluginless.rank.unknown", rank));
                                                return 0;
                                            }
                                            RankGate.setRank(ctx.getSource().getServer(), target.id(), rank);
                                            Pluginless.LOGGER.info("rank {} -> {} by {}", target.name(), rank, ctx.getSource().getTextName());
                                            ctx.getSource().sendSuccess(() -> Component.translatable("pluginless.rank.set", target.name(), rank), true);
                                            return 1;
                                        }))))
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

    private static Predicate<CommandSourceStack> operator() {
        //? if >=26.1 {
        /*return net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS);
        *///?} else {
        return source -> source.hasPermission(2);
        //?}
    }

    private static DoubleArgumentType amount() {
        return DoubleArgumentType.doubleArg(0, 1e15);
    }

    private static long cents(CommandContext<CommandSourceStack> ctx) {
        return Math.round(DoubleArgumentType.getDouble(ctx, "amount") * 100);
    }

    /** A player by name, online or not, who has an account. Hides the profile type, which changed shape after 1.21.1. */
    private record Target(UUID id, String name) {
    }

    private static Target one(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) throw ONE_PLAYER.create();
        var profile = profiles.iterator().next();
        //? if >=26.1 {
        /*var target = new Target(profile.id(), profile.name());
        *///?} else {
        var target = new Target(profile.getId(), profile.getName());
        //?}
        // A name the server has never seen still resolves to some UUID (on offline-mode servers, not even
        // the one that player would get on joining), so money sent there would be lost.
        if (!Ledger.of(ctx.getSource().getServer()).exists(target.id())) throw NEVER_PLAYED.create(target.name());
        return target;
    }

    private static String name(MinecraftServer server, UUID id) {
        //? if >=26.1 {
        /*return server.services().nameToIdCache().get(id).map(profile -> profile.name()).orElse(id.toString());
        *///?} else {
        return server.getProfileCache().get(id).map(profile -> profile.getName()).orElse(id.toString());
        //?}
    }
}
