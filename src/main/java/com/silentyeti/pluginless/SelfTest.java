package com.silentyeti.pluginless;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.FakePlayer;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/**
 * In-game feature checks, run by tools/selftest.py. They drive the real commands and items with fake
 * players, so they cover what console-only testing cannot: /pay, /roth and the terminal. Only active
 * when the JVM is started with -Dpluginless.selftest=true: the checks then run once the server has
 * started, write selftest-result.txt into the game directory, and stop the server.
 *
 * They must not run from inside a command: Minecraft queues nested commands until the outer one ends.
 *
 * To cover a new feature, add check(...) lines to run(). Assert on state (balances, open menus), not on
 * chat output.
 */
final class SelfTest {
    private final MinecraftServer server;
    private final List<String> lines = new ArrayList<>();
    private int passed;
    private int failed;

    private SelfTest(MinecraftServer server) {
        this.server = server;
    }

    static void run(MinecraftServer server) {
        var test = new SelfTest(server);
        var accounts = Ledger.of(test.server);
        // Lower-case names: an offline-mode server resolves a name it has never seen to the UUID of its
        // lower-cased form, so these are the accounts the commands below will find.
        var alice = test.player("selftest_alice");
        var bob = test.player("selftest_bob");
        var a = alice.getUUID();
        var b = bob.getUUID();

        test.as(alice, "pay selftest_bob 5");
        test.check("/pay refuses a player who has never joined", () -> !accounts.exists(a) && !accounts.exists(b));

        accounts.open(a);
        accounts.open(b);
        test.check("a new account starts at the configured start balance",
                () -> accounts.balance(a) == Config.startBalanceCents());

        accounts.set(a, 100_00);
        accounts.set(b, 100_00);
        test.as(alice, "pay selftest_bob 25.50");
        test.check("/pay moves money", () -> accounts.balance(a) == 74_50 && accounts.balance(b) == 125_50);

        test.as(alice, "pay selftest_bob 1000000");
        test.check("/pay refuses an overdraft", () -> accounts.balance(a) == 74_50 && accounts.balance(b) == 125_50);

        test.as(alice, "pay selftest_alice 5");
        test.check("/pay refuses paying yourself", () -> accounts.balance(a) == 74_50);

        test.as(alice, "eco add selftest_alice 500");
        test.check("/eco is refused for a non-operator", () -> accounts.balance(a) == 74_50);

        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "eco set selftest_bob 1");
        test.check("/eco works from the console", () -> accounts.balance(b) == 1_00);

        test.as(alice, "roth");
        test.check("/roth opens the terminal screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);

        var terminal = new ItemStack(Pluginless.ROTH_TERMINAL.get());
        alice.setItemInHand(InteractionHand.MAIN_HAND, terminal);
        terminal.use(alice.level(), alice, InteractionHand.MAIN_HAND);
        test.check("right-clicking the ROTH terminal opens the terminal screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);

        var playerHelp = test.output(alice.createCommandSourceStack(), "pluginless");
        test.check("/pluginless lists the commands a player can use, and hides /eco from a non-operator",
                () -> playerHelp.contains("/pay <player> <amount>") && playerHelp.contains("/balance") && !playerHelp.contains("/eco"));
        test.check("/pluginless help shows /eco to the console",
                () -> test.output(server.createCommandSourceStack(), "pluginless help").contains("/eco"));

        test.ranks(alice, accounts);

        // The last line is what tools/selftest.py reads.
        test.say("selftest: %d passed, %d failed".formatted(test.passed, test.failed));
        try {
            Files.write(Path.of("selftest-result.txt"), test.lines);
        } catch (IOException e) {
            Pluginless.LOGGER.error("could not write selftest-result.txt", e);
        }
        server.halt(false);
    }

    private void ranks(Tester alice, Accounts accounts) {
        var a = alice.getUUID();
        var console = server.createCommandSourceStack().withSuppressedOutput();
        check("ranks.json is created with the built-in ranks", () -> RankGate.ranks.ranks.containsKey("admin")
                && Files.exists(FMLPaths.CONFIGDIR.get().resolve("pluginless/ranks.json")));

        var configured = RankGate.ranks;
        var ranks = new Ranks();
        var normal = new Ranks.Rank();
        normal.deny = List.of("baltop");
        ranks.ranks.put("default", normal);
        var teller = new Ranks.Rank();
        teller.inherits = "default";
        teller.prefix = "&a[Teller] ";
        teller.allow = List.of("eco");
        teller.deny = List.of("eco set");
        ranks.ranks.put("teller", teller);
        RankGate.ranks = ranks;
        try {
            check("a rank can deny a command the game allows",
                    () -> !output(alice.createCommandSourceStack(), "baltop").contains("Richest"));

            server.getCommands().performPrefixedCommand(console, "rank set selftest_alice teller");
            check("/rank set gives a player a rank", () -> RankGate.rankOf(server, a).equals("teller"));

            long before = accounts.balance(a);
            as(alice, "eco add selftest_alice 5");
            check("a rank can allow an operator command", () -> accounts.balance(a) == before + 5_00);

            as(alice, "eco set selftest_alice 0");
            check("a rank can deny one subcommand of a command it allows", () -> accounts.balance(a) == before + 5_00);

            check("a rank inherits the rules of the rank below it",
                    () -> !output(alice.createCommandSourceStack(), "baltop").contains("Richest"));

            as(alice, "rank set selftest_alice default");
            check("/rank set is refused for a non-operator", () -> RankGate.rankOf(server, a).equals("teller"));

            check("/pluginless lists a command once a rank allows it",
                    () -> output(alice.createCommandSourceStack(), "pluginless").contains("/eco"));

            check("the rank prefix is put before the player's name",
                    () -> RankGate.withPrefix(alice, alice.getName()).getString().equals("\u00a7a[Teller] selftest_alice"));

            server.getCommands().performPrefixedCommand(console, "rank set selftest_alice nonsense");
            check("/rank set refuses a rank that does not exist", () -> RankGate.rankOf(server, a).equals("teller"));
        } finally {
            RankGate.ranks = configured;
        }
    }

    private Tester player(String name) {
        return new Tester(server.overworld(), new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name));
    }

    /** NeoForge's fake player refuses to open menus; this one builds the menu so a check can look at it. */
    private static final class Tester extends FakePlayer {
        private AbstractContainerMenu opened;

        Tester(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> extraData) {
            opened = provider == null ? null : provider.createMenu(1, getInventory(), this);
            return OptionalInt.empty();
        }

        AbstractContainerMenu takeOpened() {
            var menu = opened;
            opened = null;
            return menu;
        }
    }

    private void as(ServerPlayer player, String command) {
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** Everything a command printed, as plain text. */
    private String output(CommandSourceStack source, String command) {
        var text = new StringBuilder();
        server.getCommands().performPrefixedCommand(source.withSource(new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                text.append(message.getString()).append('\n');
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        }), command);
        return text.toString();
    }

    private void check(String name, BooleanSupplier ok) {
        boolean result;
        try {
            result = ok.getAsBoolean();
        } catch (RuntimeException e) {
            Pluginless.LOGGER.error("selftest check threw: {}", name, e);
            result = false;
        }
        if (result) passed++;
        else failed++;
        say((result ? "PASS  " : "FAIL  ") + name);
    }

    private void say(String line) {
        lines.add(line);
        Pluginless.LOGGER.info(line);
    }
}
