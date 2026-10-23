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
import net.minecraft.world.item.Items;
import com.mojang.serialization.JsonOps;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
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
        // Strict by default so the checks see real funding rules; round3 covers the unlimited setting.
        SelfTest.<Boolean>configValue("SERVER_UNLIMITED").set(false);
        var alice = test.player("selftest_alice");
        var bob = test.player("selftest_bob");
        var a = alice.getUUID();
        var b = bob.getUUID();

        test.as(alice, "pay selftest_bob 5");
        test.check("/pay opens never-joined accounts and credits their starting balance once", () -> accounts.exists(a) && accounts.exists(b)
                && accounts.balance(a) == Config.startBalanceCents() - 5_00 && accounts.balance(b) == Config.startBalanceCents() + 5_00);

        accounts.set(a, Config.startBalanceCents());
        accounts.set(b, Config.startBalanceCents());
        accounts.open(a);
        accounts.open(b);
        var fresh = test.player("selftest_start");
        Players.joined(fresh);
        test.check("a new account starts at the configured start balance",
                () -> accounts.balance(fresh.getUUID()) == Config.startBalanceCents() && accounts.balance(a) == Config.startBalanceCents());

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
        test.check("the ROTH terminal item does not stack", () -> terminal.getMaxStackSize() == 1);

        test.storeChecks();

        var playerHelp = test.output(alice.createCommandSourceStack(), "pluginless");
        test.check("/pluginless lists the commands a player can use, and hides /eco from a non-operator",
                () -> playerHelp.contains("/pay <player> <amount>") && playerHelp.contains("/balance") && !playerHelp.contains("/eco"));
        test.check("/pluginless help shows /eco to the console",
                () -> test.output(server.createCommandSourceStack(), "pluginless help").contains("/eco"));

        test.groundwork(alice, accounts);
        test.ranks(alice, accounts);
        test.round2();
        test.round3();
        test.check("claim compat stays off when no claim mod is installed", () -> ClaimCompat.active().isEmpty());

        // The last line is what tools/selftest.py reads.
        test.say("selftest: %d passed, %d failed".formatted(test.passed, test.failed));
        try {
            Files.write(Path.of("selftest-result.txt"), test.lines);
        } catch (IOException e) {
            Pluginless.LOGGER.error("could not write selftest-result.txt", e);
        }
        server.halt(false);
    }

    private void round2() {
        var configured = RankGate.ranks;
        var settings = Features.settings;
        var ranks = new Ranks();
        var normal = new Ranks.Rank();
        normal.perks = new java.util.LinkedHashMap<>(Map.of("homes", 1, "teleport_no_warmup", true,
                "nick", true, "namecolor", true, "namegradient", true, "skull", true, "hat", true, "title.builder", true));
        ranks.ranks.put("default", normal);
        var staff = new Ranks.Rank(); staff.inherits = "default"; staff.allow = List.of("setspawn", "setwarp", "delwarp"); ranks.ranks.put("staff", staff);
        var alice = player("round2_alice"); var bob = player("round2_bob");
        online(alice, true); online(bob, true);
        RankGate.ranks = ranks; Features.settings = new FeatureSettings();
        var warmup = SelfTest.<Integer>configValue("TELEPORT_WARMUP"); int oldWarmup = warmup.get();
        try {
            warmup.set(3);
            Players.joined(alice); Players.joined(bob);
            var accounts = Ledger.of(server); accounts.set(alice.getUUID(), 100_00); accounts.set(bob.getUUID(), 100_00); accounts.set(Accounts.SERVER, 10_000_00);
            for (int x = -8; x <= 28; x++) for (int z = -8; z <= 8; z++) {
                server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x, 240, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                for (int y = 241; y <= 243; y++) server.overworld().setBlockAndUpdate(new net.minecraft.core.BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            alice.setPos(0.5, 241, 0.5); bob.setPos(20.5, 241, 0.5);
            qol(alice, bob, accounts, normal, ranks);
            cosmeticFeatures(alice, accounts, normal, ranks);
            bankFeatures(alice, bob, accounts, normal, ranks);
            featureConfig();
            String[] choice = {""};
            RankUI.pick(() -> choice[0], value -> choice[0] = value, () -> List.of("alice", "bob"), 1);
            RankUI.pick(() -> choice[0], value -> choice[0] = value, () -> List.of("alice", "bob"), -1);
            check("server-side pickers initialize and wrap their configured choices", () -> choice[0].equals("bob"));
            RankUI.pick(() -> choice[0], value -> choice[0] = value, List::of, 1);
            check("empty server-side pickers preserve the current choice", () -> choice[0].equals("bob"));
            var help = output(alice.createCommandSourceStack(), "pluginless");
            check("new command roots are listed in /pluginless", () -> help.contains("/bank") && help.contains("/qol") && help.contains("/cosmetics")
                    && help.contains("/home") && help.contains("/namegradient") && help.contains("/nametitle"));
        } finally {
            online(alice, false); online(bob, false);
            Teleports.clear(server); Bank.clear(server);
            warmup.set(oldWarmup);
            RankGate.ranks = configured; Features.settings = settings;
        }
    }

    private void qol(Tester alice, Tester bob, Accounts accounts, Ranks.Rank normal, Ranks ranks) {
        var a = alice.getUUID();
        as(alice, "qol"); check("/qol opens homes and teleports in the terminal", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        as(alice, "balance gui"); check("terminal links include a balance screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        as(alice, "sethome");
        check("/sethome saves the default home", () -> Teleports.homesOf(server, a).containsKey("home"));
        as(alice, "sethome extra"); check("home limits come from the homes perk", () -> Teleports.homesOf(server, a).size() == 1);
        normal.perks.put("homes", 2); as(alice, "sethome extra");
        check("larger home perks allow another home", () -> Teleports.homesOf(server, a).size() == 2);
        check("/homes lists saved homes", () -> output(alice.createCommandSourceStack(), "homes").contains("extra"));
        alice.setPos(4.5, 241, 0.5); as(alice, "home");
        check("/home uses the shared teleport path", () -> alice.getX() == 0.5);
        as(alice, "back"); check("/back remembers the teleport departure", () -> alice.getX() == 4.5);
        as(alice, "delhome extra"); check("/delhome deletes only the named home", () -> !Teleports.homesOf(server, a).containsKey("extra") && Teleports.homesOf(server, a).containsKey("home"));
        var encoded = Teleports.POSITION.encodeStart(JsonOps.INSTANCE, Teleports.homesOf(server, a).get("home")).getOrThrow();
        check("saved teleport positions retain dimension coordinates and rotation", () -> Teleports.POSITION.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(Teleports.homesOf(server, a).get("home")));

        alice.setPos(8.5, 241, 0.5); as(alice, "setspawn");
        check("non-staff cannot change spawn", () -> !Teleports.destinations(server).value.containsKey("spawn"));
        RankGate.setRank(server, a, "staff"); as(alice, "setspawn"); as(alice, "setwarp test"); RankGate.setRank(server, a, "default");
        alice.setPos(0.5, 241, 0.5); as(alice, "spawn"); check("/setspawn and /spawn share a saved destination", () -> alice.getX() == 8.5);
        alice.setPos(0.5, 241, 0.5); as(alice, "warp test"); check("/warp reaches a public warp", () -> alice.getX() == 8.5 && output(alice.createCommandSourceStack(), "warp").contains("test"));
        as(alice, "delwarp test"); check("non-staff cannot delete a warp", () -> Teleports.warps(server).contains("test"));
        RankGate.setRank(server, a, "staff"); as(alice, "delwarp test"); RankGate.setRank(server, a, "default");
        check("/delwarp removes a public warp", () -> !Teleports.warps(server).contains("test"));

        Features.settings.commandCosts.put("spawn", 1.0); normal.perks.put("teleport_no_warmup", false);
        alice.setPos(0.5, 241, 0.5); long before = accounts.balance(a);
        as(alice, "spawn"); check("teleports wait for the configured warm-up without charging", () -> alice.getX() == 0.5 && accounts.balance(a) == before);
        alice.setPos(1.5, 241, 0.5); Teleports.tick(server, System.currentTimeMillis() + Config.teleportWarmupSeconds() * 1000L + 1);
        check("moving cancels warm-up and leaves money untouched", () -> alice.getX() == 1.5 && accounts.balance(a) == before);
        alice.setPos(0.5, 241, 0.5); as(alice, "spawn");
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post(alice,
                new net.neoforged.neoforge.common.damagesource.DamageContainer(alice.damageSources().generic(), 1)));
        Teleports.tick(server, System.currentTimeMillis() + Config.teleportWarmupSeconds() * 1000L + 1);
        check("damage events cancel warm-up without charging", () -> alice.getX() == 0.5 && accounts.balance(a) == before);
        long serverBefore = accounts.balance(Accounts.SERVER); as(alice, "spawn");
        Teleports.tick(server, System.currentTimeMillis() + Config.teleportWarmupSeconds() * 1000L + 1);
        check("standing still completes warm-up and pays the server", () -> alice.getX() == 8.5 && accounts.balance(a) == before - 1_00 && accounts.balance(Accounts.SERVER) == serverBefore + 1_00);
        normal.perks.put("teleport_no_warmup", true); alice.setPos(0.5, 241, 0.5); as(alice, "spawn");
        check("the teleport_no_warmup perk skips waiting", () -> alice.getX() == 8.5);
        Features.settings.commandCosts.clear();
        var safe = Teleports.position(alice);
        var floor = new net.minecraft.core.BlockPos(8, 240, 0);
        server.overworld().setBlockAndUpdate(floor, net.minecraft.world.level.block.Blocks.MAGMA_BLOCK.defaultBlockState());
        check("safe landing rejects damaging floors", () -> !Teleports.safe(server, alice, safe));
        server.overworld().setBlockAndUpdate(floor, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        server.overworld().setBlockAndUpdate(floor.above(), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
        check("safe landing rejects fluids", () -> !Teleports.safe(server, alice, safe));
        server.overworld().setBlockAndUpdate(floor.above(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        check("safe landing rejects suffocation", () -> !Teleports.safe(server, alice, safe));
        server.overworld().setBlockAndUpdate(floor.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        check("safe landing accepts a clear solid platform and rejects missing dimensions", () -> Teleports.safe(server, alice, safe)
                && !Teleports.safe(server, alice, new TeleportPosition("pluginless:missing", 0.5, 241, 0.5, 0, 0)));
        alice.setPos(3.5, 241, 0.5);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingDeathEvent(alice, alice.damageSources().generic()));
        alice.setPos(0.5, 241, 0.5); as(alice, "back"); check("death events update /back", () -> alice.getX() == 3.5);

        alice.setPos(0.5, 241, 0.5); normal.allow = List.of("tp"); as(alice, "tp 12.5 241 0.5"); normal.allow = List.of();
        Teleports.tick(server, System.currentTimeMillis()); as(alice, "back");
        check("vanilla teleport events also preserve their departure for /back", () -> alice.getX() == 0.5);
        Teleports.external(alice, arrived -> arrived.getX() == 99); alice.setPos(1.5, 241, 0.5); Teleports.tick(server, System.currentTimeMillis()); as(alice, "back");
        check("failed external teleports do not record ordinary movement as a departure", () -> alice.getX() == 12.5);
        alice.setPos(0.5, 241, 0.5); as(alice, "tpa round2_bob"); as(bob, "tpaccept");
        check("/tpa and /tpaccept teleport the requester", () -> alice.getX() == bob.getX());
        alice.setPos(0.5, 241, 0.5); as(alice, "tpahere round2_bob"); as(bob, "tpaccept");
        check("/tpahere teleports the accepting player", () -> bob.getX() == alice.getX());
        bob.setPos(20.5, 241, 0.5); as(alice, "tpa round2_bob"); as(bob, "tpdeny"); as(bob, "tpaccept");
        check("/tpdeny consumes a request without moving anyone", () -> alice.getX() == 0.5 && Teleports.requests(server).get(bob.getUUID(), System.currentTimeMillis()) == null);
        Teleports.requests(server).put(a, bob.getUUID(), new Teleports.Invitation(false, 0), 1, System.currentTimeMillis() - 2); as(bob, "tpaccept");
        check("expired teleport requests cannot be accepted", () -> alice.getX() == 0.5 && Teleports.requests(server).get(bob.getUUID(), System.currentTimeMillis()) == null);
        ranks.cooldowns.put("tpa", 30.0); as(alice, "tpa round2_bob"); as(bob, "tpdeny");
        check("QOL cooldowns use the existing shared command gate", () -> output(alice.createCommandSourceStack(), "tpa round2_bob").contains("Wait")); ranks.cooldowns.clear();
        Features.settings.rtpDimensions = List.of(); as(alice, "rtp"); check("RTP respects the dimension allow list", () -> alice.getX() == 0.5);
        Features.settings.rtpDimensions = List.of("minecraft:overworld");
        var radius = SelfTest.<Integer>configValue("RTP_RADIUS"); var attempts = SelfTest.<Integer>configValue("RTP_ATTEMPTS");
        int oldRadius = radius.get(), oldAttempts = attempts.get();
        try {
            radius.set(1); attempts.set(128); alice.setPos(20.5, 241, 0.5); as(alice, "rtp");
            check("RTP finds a safe landing inside its configured radius", () -> alice.getX() * alice.getX() + alice.getZ() * alice.getZ() <= 1 && Teleports.safe(server, alice, Teleports.position(alice)));
        } finally { radius.set(oldRadius); attempts.set(oldAttempts); }
    }

    private void cosmeticFeatures(Tester alice, Accounts accounts, Ranks.Rank normal, Ranks ranks) {
        var a = alice.getUUID();
        as(alice, "cosmetics"); check("/cosmetics opens the preview and pick screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        as(alice, "nick round2_bob"); check("nicknames cannot impersonate known real names", () -> Cosmetics.of(server, a).nick().isEmpty());
        as(alice, "nick aDmInGuy"); check("blocked nickname words ignore case", () -> Cosmetics.of(server, a).nick().isEmpty());
        as(alice, "nick " + "x".repeat(Config.nickMaxLength() + 1)); check("nickname length is enforced on the server", () -> Cosmetics.of(server, a).nick().isEmpty());
        as(alice, "nick SnowYeti"); check("/nick updates the existing name formatter", () -> RankGate.withPrefix(alice, alice.getName()).getString().equals("SnowYeti"));
        check("/realname resolves nicknames ignoring case", () -> output(alice.createCommandSourceStack(), "realname snowyeti").contains("round2_alice"));
        as(alice, "namecolor #123456"); check("/namecolor stores a validated RGB setting", () -> Cosmetics.of(server, a).color().equals("#123456") && Cosmetics.name(alice, "").getSiblings().get(0).getStyle().getColor().getValue() == 0x123456);
        as(alice, "namegradient #ff0000 #0000ff"); check("/namegradient uses shared gradient formatting", () -> Cosmetics.of(server, a).color().equals("<gradient:#ff0000:#0000ff>"));
        as(alice, "nametitle builder"); check("configured rank-unlocked titles appear next to names", () -> RankGate.withPrefix(alice, alice.getName()).getString().equals("SnowYeti [Builder]"));
        normal.chatFormat = "{name}: {message}";
        check("custom rank chat uses cosmetics through the same formatter", () -> RankGate.chat(alice, Component.literal("hello")).getString().equals("SnowYeti [Builder]: hello")); normal.chatFormat = null;
        var state = Cosmetics.of(server, a); var encoded = Cosmetics.Appearance.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        check("cosmetic codec retains nickname colour and title", () -> Cosmetics.Appearance.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(state));
        normal.perks.put("nick", false); normal.perks.put("namegradient", false); normal.perks.put("title.builder", false);
        check("rank downgrades stop rendering locked cosmetics", () -> RankGate.withPrefix(alice, alice.getName()).getString().equals("round2_alice"));
        as(alice, "nick NewYeti"); check("cosmetic permissions are checked again on each action", () -> Cosmetics.of(server, a).nick().equals("SnowYeti"));
        as(alice, "nick off"); as(alice, "namecolor off"); as(alice, "nametitle off");
        check("cosmetics can be cleared after losing their perks", () -> Cosmetics.of(server, a).equals(new Cosmetics.Appearance("", "", "")));
        check("name hooks retain another mod's display name when no cosmetic overrides it", () -> RankGate.withPrefix(alice, Component.literal("Other display")).getString().equals("Other display"));
        normal.perks.put("nick", true); normal.perks.put("namegradient", true); normal.perks.put("title.builder", true);
        var price = SelfTest.<Double>configValue("SKULL_PRICE"); double oldPrice = price.get();
        try {
            price.set(1.0); long before = accounts.balance(a), serverBefore = accounts.balance(Accounts.SERVER); int heads = items(alice, Items.PLAYER_HEAD);
            as(alice, "skull round2_bob"); check("/skull gives a profile-bearing head and pays its optional price to the server", () -> items(alice, Items.PLAYER_HEAD) == heads + 1
                    && accounts.balance(a) == before - 1_00 && accounts.balance(Accounts.SERVER) == serverBefore + 1_00
                    && headProfile(alice));
            ranks.cooldowns.put("skull", 3600.0); as(alice, "skull round2_bob");
            int cooled = items(alice, Items.PLAYER_HEAD); as(alice, "skull round2_bob"); check("skull cooldowns use the shared map", () -> items(alice, Items.PLAYER_HEAD) == cooled); ranks.cooldowns.clear();
            var full = player("round2_full"); for (int i = 0; i < 36; i++) full.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            long fullBalance = accounts.balance(full.getUUID()); as(full, "skull round2_bob");
            check("full inventories receive neither a head nor a skull charge", () -> accounts.balance(full.getUUID()) == fullBalance && items(full, Items.PLAYER_HEAD) == 0);
        } finally { price.set(oldPrice); }
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 2)); as(alice, "hat");
        check("/hat equips exactly one held item", () -> alice.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem() == Items.DIAMOND && alice.getMainHandItem().getCount() == 1);
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE)); as(alice, "hat");
        check("/hat preserves an occupied helmet slot and the held item", () -> alice.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem() == Items.DIAMOND && alice.getMainHandItem().getItem() == Items.STONE);
        cosmeticsPda(alice, accounts, normal, ranks);
        operatorCosmetics(accounts, normal);
    }

    private void operatorCosmetics(Accounts accounts, Ranks.Rank rank) {
        var player = player("cosmetic_op");
        var page = new CosmeticsPage();
        var perks = rank.perks;
        var price = SelfTest.<Double>configValue("SKULL_PRICE");
        double oldPrice = price.get();
        rank.perks = new java.util.LinkedHashMap<>();
        online(player, true);
        try {
            accounts.set(player.getUUID(), 100_00);
            check("ungranted nonoperators cannot self-apply cosmetics or get heads", () ->
                    !page.view(server, player).get("namecolor_allowed").getAsBoolean()
                    && !page.act(player, "color", List.of("#123456"), -1).ok()
                    && !page.act(player, "skull", List.of(), -1).ok());
            //? if >=26.1 {
            /*server.getPlayerList().op(player.nameAndId());
            *///?} else {
            server.getPlayerList().op(player.getGameProfile());
            //?}
            check("operators unlock valid Cosmetics controls without rank perks", () ->
                    List.of("namecolor", "namegradient", "nick", "skull", "hat").stream()
                            .allMatch(perk -> page.view(server, player).get(perk + "_allowed").getAsBoolean())
                    && !Cosmetics.allowed(player, "unknown"));
            check("operators can apply their own colour gradient nickname and title through the PDA", () ->
                    page.act(player, "color", List.of("#123456"), -1).ok()
                    && page.act(player, "gradient", List.of("#ff0000", "#0000ff"), -1).ok()
                    && page.act(player, "nick", List.of("OperatorYeti"), -1).ok()
                    && page.act(player, "title", List.of("builder"), -1).ok());
            price.set(1.0);
            long balance = accounts.balance(player.getUUID());
            check("operators receive their own profile-bearing skull and still pay its price", () ->
                    page.act(player, "skull", List.of(), -1).ok() && items(player, Items.PLAYER_HEAD) == 1
                    && headProfile(player) && accounts.balance(player.getUUID()) == balance - 100);
            accounts.set(player.getUUID(), 0);
            check("operator skull purchases cannot bypass insufficient funds", () ->
                    !page.act(player, "skull", List.of(), -1).ok() && items(player, Items.PLAYER_HEAD) == 1);
            player.getInventory().setItem(35, new ItemStack(Items.EMERALD, 2));
            check("operators can wear exactly one selected inventory item", () ->
                    page.act(player, "hat", List.of(), 35).ok()
                    && player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.EMERALD)
                    && player.getInventory().getItem(35).getCount() == 1);
        } finally {
            //? if >=26.1 {
            /*server.getPlayerList().deop(player.nameAndId());
            *///?} else {
            server.getPlayerList().deop(player.getGameProfile());
            //?}
            check("removing operator status immediately restores cosmetic locks", () ->
                    !page.view(server, player).get("nick_allowed").getAsBoolean()
                    && !page.act(player, "nick", List.of("NoLongerOp"), -1).ok()
                    && Cosmetics.of(server, player.getUUID()).nick().equals("OperatorYeti"));
            rank.perks = perks;
            price.set(oldPrice);
            online(player, false);
        }
    }

    private void cosmeticsPda(Tester alice, Accounts accounts, Ranks.Rank rank, Ranks ranks) {
        var page = new CosmeticsPage();
        var id = alice.getUUID();
        check("PDA cosmetics snapshot uses live perk and inventory state", () -> page.view(server, alice).get("hat_allowed").getAsBoolean()
                && !page.view(server, alice).get("head_empty").getAsBoolean() && page.view(server, alice).getAsJsonArray("slots").size() == 36);
        check("PDA nickname intent goes through the existing service", () -> page.act(alice, "nick", List.of("PdaYeti"), -1).ok()
                && Cosmetics.of(server, id).nick().equals("PdaYeti"));
        rank.perks.put("nick", false);
        check("PDA rejects a stale unlocked nickname snapshot", () -> !page.act(alice, "nick", List.of("ForgedYeti"), -1).ok()
                && Cosmetics.of(server, id).nick().equals("PdaYeti"));
        check("PDA still clears a nickname after its perk is lost", () -> page.act(alice, "nick", List.of("off"), -1).ok()
                && Cosmetics.of(server, id).nick().isEmpty());
        rank.perks.put("nick", true);
        var deny = rank.deny;
        try {
            rank.deny = List.of("nick");
            check("PDA cannot bypass command rank denial", () -> !page.act(alice, "nick", List.of("Forbidden"), -1).ok()
                    && Cosmetics.of(server, id).nick().isEmpty());
        } finally { rank.deny = deny; }
        ranks.cooldowns.put("nick", 3600.0);
        try {
            page.act(alice, "nick", List.of("FirstPda"), -1);
            check("PDA shares command cooldowns", () -> !page.act(alice, "nick", List.of("SecondPda"), -1).ok()
                    && Cosmetics.of(server, id).nick().equals("FirstPda"));
        } finally { ranks.cooldowns.remove("nick"); }
        var saved = Cosmetics.of(server, id);
        check("PDA rejects malformed colour markup without saving", () -> !page.act(alice, "color", List.of("#ff0000&l"), -1).ok()
                && Cosmetics.of(server, id).equals(saved));
        check("PDA rejects injected nicknames, selectors, extra arguments and unknown verbs", () ->
                !page.act(alice, "nick", List.of("Alice kill @a"), -1).ok()
                && !page.act(alice, "skull", List.of("@a"), -1).ok()
                && !page.act(alice, "title", List.of("builder\nop round2_alice"), -1).ok()
                && !page.act(alice, "color", List.of("&c", "ignored"), -1).ok()
                && !page.act(alice, "gradient", List.of("#ff0000", "&k"), -1).ok()
                && !page.act(alice, "op", List.of("round2_alice"), -1).ok()
                && Cosmetics.of(server, id).equals(saved));
        var head = net.minecraft.world.entity.EquipmentSlot.HEAD;
        alice.setItemSlot(head, ItemStack.EMPTY);
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        alice.getInventory().setItem(35, new ItemStack(Items.EMERALD, 3));
        check("PDA slot hat equips one server-side item and preserves the held item", () -> page.act(alice, "hat", List.of(), 35).ok()
                && alice.getItemBySlot(head).getItem() == Items.EMERALD && alice.getInventory().getItem(35).getCount() == 2
                && alice.getMainHandItem().getItem() == Items.STONE);
        check("PDA cannot overwrite an occupied helmet or use a forged slot", () -> !page.act(alice, "hat", List.of(), 35).ok()
                && !page.act(alice, "hat", List.of(), 40).ok() && alice.getInventory().getItem(35).getCount() == 2);
        var price = SelfTest.<Double>configValue("SKULL_PRICE"); double oldPrice = price.get();
        try {
            price.set(1.0);
            long before = accounts.balance(id); int heads = items(alice, Items.PLAYER_HEAD);
            check("PDA self skull uses the server price and normal item delivery", () -> page.act(alice, "skull", List.of(), -1).ok()
                    && accounts.balance(id) == before - 100 && items(alice, Items.PLAYER_HEAD) == heads + 1);
        } finally { price.set(oldPrice); }
    }

    private static boolean headProfile(ServerPlayer player) {
        for (int i = 0; i < 36; i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.getItem() == Items.PLAYER_HEAD && stack.get(net.minecraft.core.component.DataComponents.PROFILE) != null) return true;
        }
        return false;
    }

    private void bankFeatures(Tester alice, Tester bob, Accounts accounts, Ranks.Rank normal, Ranks ranks) {
        var a = alice.getUUID(); var b = bob.getUUID();
        as(alice, "bank"); check("/bank opens the balance send-money and history screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        check("bank history and debt summary use the shared ledger", () -> BankUI.summary(alice).getString().contains("Debt") && Bank.history(server, a).getString().contains("teleport.fee"));
        long before = accounts.balance(a); as(alice, "bank request round2_bob 2"); as(bob, "bank accept");
        check("accepting a payment request moves the stored amount exactly once", () -> accounts.balance(a) == before + 2_00);
        as(bob, "bank accept"); check("a payment request cannot be accepted twice", () -> accounts.balance(a) == before + 2_00);
        as(alice, "bank request round2_bob 2"); as(bob, "bank decline");
        check("declining a payment request moves no money", () -> accounts.balance(a) == before + 2_00 && Bank.requests(server).get(b, System.currentTimeMillis()) == null);
        Bank.requests(server).put(a, b, 2_00L, 1, System.currentTimeMillis() - 2); as(bob, "bank accept");
        check("expired payment requests cannot be paid", () -> accounts.balance(a) == before + 2_00);
        long serverBefore = accounts.balance(Accounts.SERVER), borrowerBefore = accounts.balance(a); as(alice, "bank borrow 5");
        check("loans transfer existing server funds and record principal debt", () -> accounts.balance(a) == borrowerBefore + 5_00 && Bank.debt(server, a) == 5_00 && accounts.balance(Accounts.SERVER) == serverBefore - 5_00);
        as(alice, "bank borrow 1"); check("unpaid debt blocks new borrowing", () -> Bank.debt(server, a) == 5_00 && accounts.balance(a) == borrowerBefore + 5_00);
        long credited = accounts.balance(a); as(bob, "pay round2_alice 2");
        check("player transfers repay debt before becoming spendable income", () -> Bank.debt(server, a) == 3_00 && accounts.balance(a) == credited && accounts.balance(Accounts.SERVER) == serverBefore - 3_00);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "eco add round2_alice 1");
        check("issued income also repays debt first", () -> Bank.debt(server, a) == 2_00 && accounts.balance(a) == credited);
        var kit = new Ranks.Kit(); kit.money = 2; ranks.kits.put("banktest", kit); normal.perks.put("kit.banktest", true); as(alice, "kit banktest");
        check("kit and reward money use the same income garnishment", () -> Bank.debt(server, a) == 0 && accounts.balance(a) == credited);
        as(alice, "bank borrow 4"); Bank.tick(server, System.currentTimeMillis() + Debts.DAY + 1000);
        long due = 4_00 + (long) Math.ceil(4_00 * Config.loanDailyInterest() / 100);
        check("daily loan interest accrues even for stored offline debts", () -> Bank.debt(server, a) == due && Ledger.history(server, a).stream().anyMatch(t -> t.reason().equals("debt.interest")));
        var debt = new Debts.Debt(due, 4_00, System.currentTimeMillis()); var encoded = Bank.DEBT.encodeStart(JsonOps.INSTANCE, debt).getOrThrow();
        check("debt codec retains amount principal and interest deadline", () -> Bank.DEBT.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(debt));
        as(alice, "bank repay " + java.math.BigDecimal.valueOf(due, 2).toPlainString());
        check("/bank repay clears debt from the existing balance", () -> Bank.debt(server, a) == 0);
        normal.perks.put("loan_limit", 2); as(alice, "bank borrow 3"); check("borrowing limits are adjusted by rank perks", () -> Bank.debt(server, a) == 0);
        accounts.set(Accounts.SERVER, 0); as(alice, "bank borrow 1"); check("an empty server account cannot create loan money", () -> Bank.debt(server, a) == 0);
        accounts.set(Accounts.SERVER, Long.MAX_VALUE); check("later modules can add debt without issuing money", () -> Bank.addDebt(server, a, 10, "fine") && Bank.debt(server, a) == 10);
        long held = accounts.balance(a), payer = accounts.balance(b); as(bob, "pay round2_alice 0.01");
        check("garnishment overflow leaves payer recipient and debt unchanged", () -> accounts.balance(a) == held && accounts.balance(b) == payer && Bank.debt(server, a) == 10);
        accounts.set(Accounts.SERVER, 10_000_00); as(alice, "bank repay 0.10");
        check("income debt repayments are recorded in the transaction log", () -> Ledger.history(server, a).stream().anyMatch(t -> t.reason().endsWith("debt.income")));
        var never = Players.resolve(server, "round2_new"); accounts.set(never, 77); Bank.addDebt(server, never, 50, "margin");
        var joining = new Tester(server.overworld(), new GameProfile(java.util.UUID.randomUUID(), "round2_new")); Players.joined(joining);
        check("provisional debt follows a never-joined account to its login UUID", () -> Bank.debt(server, joining.getUUID()) == 50 && Bank.debt(server, never) == 0 && accounts.balance(joining.getUUID()) == 77);
        var poor = player("round2_poor"); accounts.set(poor.getUUID(), 0); as(poor, "bank borrow 1"); accounts.withdraw(poor.getUUID(), 1_00);
        long lender = accounts.balance(Accounts.SERVER); as(poor, "bank repay 0.50");
        check("an unaffordable repayment changes neither debt nor lender balance", () -> Bank.debt(server, poor.getUUID()) == 1_00
                && accounts.balance(poor.getUUID()) == 0 && accounts.balance(Accounts.SERVER) == lender);
        accounts.deposit(poor.getUUID(), 1_00);
    }

    private void round3() {
        var configured = RankGate.ranks;
        var settings = Features.settings;
        var ranks = new Ranks();
        var normal = new Ranks.Rank();
        normal.perks = new java.util.LinkedHashMap<>();
        ranks.ranks.put("default", normal);
        var alice = player("round3_alice"); var bob = player("round3_bob");
        var a = alice.getUUID(); var b = bob.getUUID();
        var unlimited = SelfTest.<Boolean>configValue("SERVER_UNLIMITED");
        var transferTax = SelfTest.<Double>configValue("TRANSFER_TAX");
        var grace = SelfTest.<Integer>configValue("AUCTION_GRACE");
        online(alice, true); online(bob, true);
        RankGate.ranks = ranks; Features.settings = new FeatureSettings();
        try {
            Players.joined(alice); Players.joined(bob);
            var accounts = Ledger.of(server);
            accounts.set(a, 100_00); accounts.set(b, 100_00); accounts.set(Accounts.SERVER, 0);
            var console = server.createCommandSourceStack().withSuppressedOutput();

            as(alice, "nick Zed"); check("cosmetics stay locked without a rank perk or grant", () -> Cosmetics.of(server, a).nick().isEmpty());
            as(alice, "cosmetic grant round3_alice nick"); check("players cannot grant themselves cosmetics", () -> !Cosmetics.allowed(alice, "nick"));
            server.getCommands().performPrefixedCommand(console, "cosmetic grant round3_alice nick");
            as(alice, "nick Zed"); check("a console grant unlocks a cosmetic for one player", () -> Cosmetics.of(server, a).nick().equals("Zed") && !Cosmetics.allowed(bob, "nick"));
            server.getCommands().performPrefixedCommand(console, "cosmetic grant round3_alice wings");
            check("unknown cosmetics are refused", () -> !Cosmetics.allowed(alice, "wings"));
            var never = Players.resolve(server, "round3_new"); Cosmetics.grant(server, never, "hat", true);
            var joined = java.util.UUID.randomUUID(); Cosmetics.move(server, never, joined);
            check("grants made before first join carry to the joining player", () -> Cosmetics.grant(server, joined, "hat", false) && !Cosmetics.grant(server, never, "hat", false));
            server.getCommands().performPrefixedCommand(console, "cosmetic revoke round3_alice nick");
            check("a revoked grant locks the cosmetic again", () -> !Cosmetics.allowed(alice, "nick"));

            Income.login(alice);
            check("an empty server account pays no income when it may not issue money", () -> accounts.balance(a) == 100_00);
            unlimited.set(true);
            Income.clear();
            // The streak was recorded by the refused login above, so use bob for the paid one.
            Income.login(bob); check("the daily login reward is issued once per day", () -> accounts.balance(b) == 100_00 + Math.round(Config.dailyReward() * 100));
            Income.login(bob); check("a second login the same day pays nothing", () -> accounts.balance(b) == 100_00 + Math.round(Config.dailyReward() * 100));
            accounts.set(b, 100_00);
            Income.online(alice, Config.paycheckSeconds() - 1); check("no paycheck before the interval", () -> accounts.balance(a) == 100_00);
            Income.online(alice, 1); check("a paycheck is paid after the interval", () -> accounts.balance(a) == 100_00 + Math.round(Config.paycheck() * 100));
            normal.perks.put("income_multiplier", 2); accounts.set(a, 100_00);
            Income.online(alice, Config.paycheckSeconds() * 5); check("paychecks scale with the rank multiplier and never pay a backlog", () -> accounts.balance(a) == 100_00 + Math.round(Config.paycheck() * 200));
            normal.perks.remove("income_multiplier"); accounts.set(a, 100_00);
            Income.kill(alice, "minecraft:zombie"); Income.kill(alice, "minecraft:bat");
            check("configured mobs pay a kill reward and others pay nothing", () -> accounts.balance(a) == 101_00);
            var cap = SelfTest.<Integer>configValue("MOB_CAP"); int oldCap = cap.get(); cap.set(1);
            Income.kill(alice, "minecraft:zombie"); check("the hourly kill cap stops farm income", () -> accounts.balance(a) == 101_00);
            cap.set(oldCap); accounts.set(a, 100_00);

            alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 2));
            check("/worth prices the held stack", () -> Income.worth(alice.getMainHandItem()) == 50_00);
            unlimited.set(false); as(alice, "sell");
            check("a sale the server cannot fund keeps the item", () -> alice.getMainHandItem().getCount() == 2 && accounts.balance(a) == 100_00);
            unlimited.set(true); as(alice, "sell");
            check("/sell pays the fixed price and takes the stack", () -> alice.getMainHandItem().isEmpty() && accounts.balance(a) == 150_00);
            alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT)); as(alice, "sell");
            check("items without a price cannot be sold", () -> !alice.getMainHandItem().isEmpty() && accounts.balance(a) == 150_00);

            accounts.set(a, 100_00); accounts.set(b, 100_00); accounts.set(Accounts.SERVER, 0); transferTax.set(10.0);
            as(alice, "pay round3_bob 10");
            check("the transfer tax is paid by the sender to the server", () -> accounts.balance(a) == 89_00 && accounts.balance(b) == 110_00 && accounts.balance(Accounts.SERVER) == 1_00);
            accounts.set(a, 10_50); as(alice, "pay round3_bob 10");
            check("a payment whose tax cannot be covered moves nothing", () -> accounts.balance(a) == 10_50 && accounts.balance(b) == 110_00 && accounts.balance(Accounts.SERVER) == 1_00);
            normal.perks.put("fee_multiplier", 0.5); accounts.set(a, 100_00); as(alice, "pay round3_bob 10");
            check("rank perks lower the tax rate", () -> accounts.balance(a) == 89_50);
            normal.perks.remove("fee_multiplier"); transferTax.set(0.0);
            check("/taxes lists what was paid and why", () -> output(alice.createCommandSourceStack(), "taxes").contains("pay.tax"));
            as(alice, "taxes gui"); check("/taxes gui opens the tax screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);

            gating(alice, bob, accounts);
            auctions(alice, bob, accounts, normal, grace);
            market(alice, bob, accounts, normal);
            companies(alice, bob, accounts, normal);
            stocks(alice, bob, accounts);
            shares(alice, bob, accounts);
        } finally {
            online(alice, false); online(bob, false);
            unlimited.set(false); transferTax.set(0.0); grace.set(20); Income.clear();
            RankGate.ranks = configured; Features.settings = settings;
        }
    }

    /** Store: one cached instance per name, changes survive a save to disk, and an unparsable value is an error rather than an empty store. */
    private void storeChecks() {
        var codec = com.mojang.serialization.Codec.STRING.listOf().xmap(java.util.ArrayList<String>::new, list -> list);
        var store = Store.open(server, "selftest_store", codec, java.util.ArrayList::new);
        store.value.add("marker-" + 42);
        store.setDirty();
        check("opening a store again returns the same live value", () -> Store.open(server, "selftest_store", codec, java.util.ArrayList::new).value.contains("marker-42"));
        //? if >=26.1 {
        /*server.overworld().getDataStorage().saveAndJoin();
        *///?} else {
        server.overworld().getDataStorage().save();
        //?}
        // NeoForge writes saved data through a temporary file and a rename, so wait for the real file to appear.
        check("a changed store is written to the world save", () -> {
            for (int attempt = 0; attempt < 50; attempt++) {
                try (var files = java.nio.file.Files.walk(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT))) {
                    var file = files.filter(path -> path.getFileName().toString().endsWith("pluginless_selftest_store.dat")
                            || path.getFileName().toString().equals("selftest_store.dat")).findFirst();
                    if (file.isPresent()) {
                        try (var in = new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file.get()))) {
                            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("marker-42");
                        }
                    }
                    Thread.sleep(100);
                } catch (java.io.IOException e) { return false; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            return false;
        });
        check("a value that does not match its codec is an error, never an empty store", () -> {
            try { codec.parse(com.mojang.serialization.JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(5)).getOrThrow(); return false; }
            catch (RuntimeException e) { return true; }
        });
    }

    /** Who may run what, what malformed arguments do, and how never-joined targets behave. */
    private void gating(Tester alice, Tester bob, Accounts accounts) {
        var a = alice.getUUID(); var b = bob.getUUID();
        accounts.set(a, 100_00); accounts.set(b, 100_00); accounts.set(Accounts.SERVER, 0);
        var source = alice.createCommandSourceStack();
        for (String command : List.of("eco add round3_bob 5", "eco set round3_alice 1000", "setspawn", "rank set round3_alice default", "rank reload", "stock freeze x"))
            check("a non-operator cannot run /" + command, () -> output(source, command).contains("Unknown or incomplete") || output(source, command).contains("Incorrect argument") || output(source, command).toLowerCase().contains("permission"));
        check("operator commands changed no balance for a non-operator", () -> accounts.balance(a) == 100_00 && accounts.balance(b) == 100_00);
        for (String command : List.of("pay round3_bob -5", "pay round3_bob abc", "pay round3_bob 1e16", "pay round3_bob", "pay", "ah bid 1 -5", "ah buy 0", "ah buy abc", "bank borrow -1"))
            check("malformed /" + command + " is refused", () -> { String out = output(source, command); return !out.isEmpty() && accounts.balance(a) == 100_00 && accounts.balance(b) == 100_00; });
        check("paying yourself moves nothing", () -> { output(source, "pay round3_alice 5"); return accounts.balance(a) == 100_00; });
        var ghost = Players.resolve(server, "round3_ghost");
        check("a never-joined target has no account until money reaches it", () -> !accounts.exists(ghost));
        as(alice, "pay round3_ghost 5");
        check("paying a never-joined player opens their account with the starting balance", () -> accounts.exists(ghost) && accounts.balance(ghost) == Config.startBalanceCents() + 5_00 && accounts.balance(a) == 95_00);
        check("a console operator can run the operator commands", () -> { server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "eco add round3_ghost 1");
            return accounts.balance(ghost) == Config.startBalanceCents() + 6_00; });
    }

    private void auctions(Tester alice, Tester bob, Accounts accounts, Ranks.Rank normal, net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<Integer> grace) {
        var a = alice.getUUID(); var b = bob.getUUID();
        var book = Auctions.store(server).value;
        accounts.set(a, 100_00); accounts.set(b, 100_00); accounts.set(Accounts.SERVER, 0);
        as(alice, "ah"); check("/ah opens the auction screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        var pick = new ItemStack(Items.DIAMOND_PICKAXE);
        pick.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Drill"));
        alice.setItemInHand(InteractionHand.MAIN_HAND, pick);
        as(alice, "ah sell 50");
        check("/ah sell lists the held item and charges the listing fee", () -> alice.getMainHandItem().isEmpty() && book.count(a) == 1
                && accounts.balance(a) == 99_50 && accounts.balance(Accounts.SERVER) == 50);
        long id = book.listings.keySet().iterator().next();
        as(bob, "ah buy " + id); check("a new listing cannot be bought during the grace period", () -> book.listings.containsKey(id) && accounts.balance(b) == 100_00);
        grace.set(0);
        as(alice, "ah buy " + id); check("a seller cannot buy their own listing", () -> book.listings.containsKey(id));
        check("/ah view and /ah list show the listing", () -> output(bob.createCommandSourceStack(), "ah view " + id).contains("Drill")
                && output(bob.createCommandSourceStack(), "ah list drill").contains("Drill") && !output(bob.createCommandSourceStack(), "ah list bread").contains("Drill"));
        as(bob, "ah buy " + id);
        check("a purchase pays the seller less sales tax and boxes the item", () -> !book.listings.containsKey(id) && accounts.balance(b) == 50_00
                && accounts.balance(a) == 99_50 + 49_00 && accounts.balance(Accounts.SERVER) == 50 + 1_00 && book.boxes.get(b).size() == 1);
        as(bob, "ah buy " + id); as(alice, "ah cancel " + id); Auctions.tick(server, System.currentTimeMillis() + Config.auctionMillis() + 1);
        check("a sold item cannot be bought again, cancelled or expired back to the seller", () -> accounts.balance(b) == 50_00 && !book.boxes.containsKey(a) && book.boxes.get(b).size() == 1);
        as(bob, "ah collect");
        check("collecting restores the item with its data", () -> bob.getInventory().getItem(0).is(Items.DIAMOND_PICKAXE) && bob.getInventory().getItem(0).getHoverName().getString().equals("Drill"));
        as(bob, "ah collect"); check("an item cannot be collected twice", () -> bob.getInventory().getItem(1).isEmpty() && !book.boxes.containsKey(b));
        check("sales feed the price history", () -> book.history.get("minecraft:diamond_pickaxe").get(0).unitPrice() == 50_00);

        normal.perks.put("auction_slots", 1);
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD, 3)); as(alice, "ah sell 5");
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE)); as(alice, "ah sell 5");
        check("the listing limit comes from the rank perk", () -> book.count(a) == 1 && alice.getMainHandItem().is(Items.APPLE));
        normal.perks.put("auction_slots", 5);
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BEDROCK)); as(alice, "ah sell 5");
        check("blocked items cannot be listed", () -> alice.getMainHandItem().is(Items.BEDROCK) && book.count(a) == 1);
        check("prices outside the limits are refused", () -> !Auctions.sell(alice, 0) && !Auctions.sell(alice, Config.auctionMaxCents() + 1));
        long bread = book.listings.keySet().iterator().next();
        check("the category filter finds the listing", () -> book.view("", Features.settings.auctionCategories.get("food"), "price", System.currentTimeMillis()).size() == 1
                && book.view("", Features.settings.auctionCategories.get("tools"), "price", System.currentTimeMillis()).isEmpty());
        as(bob, "ah cancel " + bread); check("only the seller can cancel a listing", () -> book.listings.containsKey(bread));
        check("an operator can remove any listing, returning it to the seller", () -> Auctions.cancel(bob, bread, true) && book.boxes.get(a).size() == 1);
        alice.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY); as(alice, "ah collect");
        check("removed listings are collected by the seller", () -> items(alice, Items.BREAD) == 3);

        as(bob, "ah watch minecraft:apple"); online(bob, false);
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.APPLE)); as(alice, "ah sell 5");
        long apple = book.listings.keySet().iterator().next();
        check("watchers are told about a matching listing, on next login if offline", () -> book.notices.get(b).get(0).kind().equals("watch"));
        online(bob, true); Auctions.deliver(server); check("notices are delivered once the player is online", () -> !book.notices.containsKey(b));
        online(alice, false); Auctions.tick(server, System.currentTimeMillis() + Config.auctionMillis() + 1);
        check("an expired listing returns to the seller's box with a notice", () -> !book.listings.containsKey(apple) && book.boxes.get(a).size() == 1
                && book.notices.get(a).get(0).kind().equals("expired"));
        online(alice, true);

        accounts.set(a, 100_00); accounts.set(b, 100_00); accounts.set(Accounts.SERVER, 0);
        alice.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND)); as(alice, "ah auction 10");
        long auction = book.listings.keySet().iterator().next();
        check("/ah auction lists the held item as a bidding auction", () -> alice.getMainHandItem().isEmpty() && book.listings.get(auction).auction());
        accounts.set(a, 100_00);
        as(bob, "ah buy " + auction); as(alice, "ah bid " + auction + " 10"); as(bob, "ah bid " + auction + " 9");
        check("an auction cannot be bought outright, bid on by its seller or bid under the start price", () -> book.listings.get(auction).bidder() == null && accounts.balance(b) == 100_00);
        as(bob, "ah bid " + auction + " 10");
        check("a bid is held in escrow", () -> b.equals(book.listings.get(auction).bidder()) && accounts.balance(b) == 90_00 && accounts.balance(Accounts.ESCROW) == 10_00);
        as(bob, "ah bid " + auction + " 10.2");
        check("a bid under the minimum raise is refused", () -> book.listings.get(auction).bid() == 10_00);
        as(bob, "ah cancel " + auction); as(alice, "ah cancel " + auction);
        check("a listing with a bid cannot be cancelled by its seller", () -> book.listings.containsKey(auction));
        as(bob, "ah bid " + auction + " 20");
        check("raising your own bid escrows only the difference", () -> accounts.balance(b) == 80_00 && accounts.balance(Accounts.ESCROW) == 20_00);
        var auctionCopy = Auctions.CODEC.parse(JsonOps.INSTANCE, Auctions.CODEC.encodeStart(JsonOps.INSTANCE, book).getOrThrow()).getOrThrow();
        check("the auction codec preserves bids", () -> auctionCopy.listings.get(auction).equals(book.listings.get(auction)));
        Auctions.tick(server, book.listings.get(auction).expires());
        check("an ended auction pays the seller less tax from escrow and boxes the item for the winner once", () -> !book.listings.containsKey(auction)
                && accounts.balance(a) == 100_00 + 19_60 && accounts.balance(Accounts.SERVER) == 10 + 40 && accounts.balance(Accounts.ESCROW) == 0 && book.boxes.get(b).size() == 1);
        Auctions.deliver(server);
        as(bob, "ah collect"); check("the winner collects the item", () -> items(bob, Items.DIAMOND) == 1);

        String shulker = "{\"id\":\"minecraft:shulker_box\",\"count\":1,\"components\":{\"minecraft:container\":[{\"slot\":0,\"item\":{\"id\":\"minecraft:bread\",\"count\":16}}]}}";
        check("container contents can be previewed from a stored item", () -> Stacks.contents(Stacks.write(server, Stacks.read(server, shulker))).equals(List.of("16 minecraft:bread")));
        var encoded = Auctions.CODEC.encodeStart(JsonOps.INSTANCE, book).getOrThrow();
        check("the auction codec preserves boxes, watches and history", () -> {
            var copy = Auctions.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            return copy.boxes.equals(book.boxes) && copy.watches.equals(book.watches) && copy.history.equals(book.history) && copy.nextId == book.nextId;
        });
        var help = output(alice.createCommandSourceStack(), "pluginless");
        check("the new commands are listed in /pluginless", () -> help.contains("/ah") && help.contains("/sell") && help.contains("/worth") && help.contains("/taxes") && !help.contains("/cosmetic "));
    }

    private void market(Tester alice, Tester bob, Accounts accounts, Ranks.Rank normal) {
        var a = alice.getUUID(); var b = bob.getUUID();
        var book = Market.store(server).value;
        for (var player : List.of(alice, bob)) for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        accounts.set(a, 100_00); accounts.set(b, 100_00); accounts.set(Accounts.SERVER, 0);
        long money = accounts.balance(a) + accounts.balance(b) + accounts.balance(Accounts.ESCROW);
        as(alice, "market"); check("/market opens the market screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        as(bob, "market buy minecraft:iron_ingot 10 2");
        check("a buy order holds its money in escrow", () -> accounts.balance(b) == 80_00 && accounts.balance(Accounts.ESCROW) == 20_00 && book.count(b) == 1);
        as(bob, "market buy minecraft:dirt 1 1"); check("only configured items trade on the market", () -> book.count(b) == 1);
        var named = new ItemStack(Items.IRON_INGOT, 8); named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Lucky"));
        alice.getInventory().setItem(0, named); alice.getInventory().setItem(1, new ItemStack(Items.IRON_INGOT, 4));
        as(alice, "market sell minecraft:iron_ingot 6 1");
        check("a sell order needs enough plain items, and renamed ones do not count", () -> alice.getInventory().getItem(1).getCount() == 4 && book.count(a) == 0);
        as(alice, "market sell minecraft:iron_ingot 4 1.50");
        long fee = Taxes.percent(4 * 2_00, Config.marketFee(), 1);
        check("a sell order fills a waiting buyer at the buyer's price, less the trading fee", () -> alice.getInventory().getItem(1).isEmpty()
                && alice.getInventory().getItem(0).getCount() == 8 && accounts.balance(a) == 100_00 + 8_00 - fee && accounts.balance(Accounts.SERVER) == fee
                && book.goods.get(b).get("minecraft:iron_ingot") == 4 && book.side("minecraft:iron_ingot", true).get(0).remaining() == 6);
        as(bob, "market collect");
        check("bought goods are collected into the inventory once", () -> items(bob, Items.IRON_INGOT) == 4 && !book.goods.containsKey(b) && Market.collect(bob) == 0);
        long order = book.side("minecraft:iron_ingot", true).get(0).id();
        as(alice, "market cancel " + order); check("only the owner can cancel an order", () -> book.orders.containsKey(order));
        as(bob, "market cancel " + order);
        check("cancelling a buy order returns the escrowed money", () -> accounts.balance(b) == 100_00 - 8_00 && accounts.balance(Accounts.ESCROW) == 0);
        as(bob, "market sell minecraft:iron_ingot 4 3"); as(alice, "market instabuy minecraft:iron_ingot 5");
        check("an instant order that cannot be filled completely does nothing", () -> book.side("minecraft:iron_ingot", false).get(0).remaining() == 4 && !book.goods.containsKey(a));
        as(alice, "market instabuy minecraft:iron_ingot 3");
        check("an instant buy fills against the best sell order", () -> book.goods.get(a).get("minecraft:iron_ingot") == 3 && book.side("minecraft:iron_ingot", false).get(0).remaining() == 1);
        long resting = book.side("minecraft:iron_ingot", false).get(0).id();
        as(bob, "market cancel " + resting); as(bob, "market collect");
        check("cancelling a sell order returns the unsold goods", () -> items(bob, Items.IRON_INGOT) == 1 && book.count(b) == 0);
        normal.perks.put("market_orders", 1);
        as(bob, "market buy minecraft:coal 1 1"); as(bob, "market buy minecraft:coal 1 1");
        check("the open-order limit comes from the rank perk", () -> book.count(b) == 1);
        normal.perks.remove("market_orders");
        check("the market neither creates nor destroys money", () -> accounts.balance(a) + accounts.balance(b) + accounts.balance(Accounts.ESCROW) + accounts.balance(Accounts.SERVER) == money);
        check("/market book charts the trade history", () -> output(alice.createCommandSourceStack(), "market book minecraft:iron_ingot").contains("▁"));
        var encoded = Market.CODEC.encodeStart(JsonOps.INSTANCE, book).getOrThrow();
        check("the market codec preserves orders, goods and history", () -> {
            var copy = Market.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            return copy.orders.equals(book.orders) && copy.goods.equals(book.goods) && copy.history.equals(book.history) && copy.nextId == book.nextId;
        });
        check("escrow is not a player on /baltop", () -> accounts.top(100).stream().noneMatch(entry -> entry.getKey().equals(Accounts.ESCROW)));
    }

    private void companies(Tester alice, Tester bob, Accounts accounts, Ranks.Rank normal) {
        var a = alice.getUUID(); var b = bob.getUUID();
        var book = Companies.store(server).value;
        for (var player : List.of(alice, bob)) for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        Market.collect(alice); Market.collect(bob);
        for (var player : List.of(alice, bob)) for (int i = 0; i < 36; i++) player.getInventory().setItem(i, ItemStack.EMPTY);
        accounts.set(a, 1000_00); accounts.set(b, 100_00); accounts.set(Accounts.SERVER, 0);
        as(alice, "company"); check("/company opens the company screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        Bank.addDebt(server, a, 1_00, "test"); as(alice, "company create Acme");
        check("a debtor cannot found a company", () -> book.company("Acme") == null);
        Bank.repay(server, a, 1_00); accounts.set(a, 1000_00); accounts.set(Accounts.SERVER, 0);
        as(alice, "company create Acme");
        check("founding a company charges the fee", () -> book.company("Acme") != null && accounts.balance(a) == 1000_00 - Config.companyFeeCents() && accounts.balance(Accounts.SERVER) == Config.companyFeeCents());
        as(alice, "company create Second"); check("the company limit comes from config or the rank perk", () -> book.company("Second") == null);
        var acme = book.company("Acme"); var account = CompanyBook.account(acme.id);
        var pda = new CompaniesPage();
        check("PDA company deposit uses the real command", () -> pda.act(alice, "deposit", List.of("Acme", "100"), -1).ok());
        as(bob, "company withdraw Acme 10");
        check("only staff with the right role can withdraw", () -> accounts.balance(account) == 100_00 && accounts.balance(b) == 100_00);
        check("PDA refuses forged withdrawals and command injection without moving money", () ->
                !pda.act(bob, "withdraw", List.of("Acme", "10"), -1).ok()
                && !pda.act(bob, "hire", List.of("Acme", "round3_bob manager", "manager"), -1).ok()
                && accounts.balance(account) == 100_00 && accounts.balance(b) == 100_00 && acme.members.isEmpty());
        var denied = normal.deny;
        normal.deny = List.of("company deposit");
        try {
            check("PDA company actions preserve rank command restrictions", () ->
                    !pda.act(alice, "deposit", List.of("Acme", "1"), -1).ok() && accounts.balance(account) == 100_00);
        } finally { normal.deny = denied; }
        as(bob, "company hire Acme round3_bob manager"); check("outsiders cannot hire themselves", () -> acme.members.isEmpty());
        as(alice, "company hire Acme round3_bob employee"); as(alice, "company wage Acme round3_bob 5");
        check("PDA employee snapshot hides spend/management rights and rejects wage edits", () -> {
            var view = pda.view(server, bob).getAsJsonObject("company");
            return !view.get("spend").getAsBoolean() && !view.get("manage").getAsBoolean()
                    && !pda.act(bob, "wage", List.of("Acme", "round3_bob", "500"), -1).ok() && acme.wages.get(b) == 5_00;
        });
        book.lastPayroll = 1; Companies.tick(server, 1 + Config.payrollMillis());
        check("payroll pays wages from the company account", () -> accounts.balance(b) == 105_00 && accounts.balance(account) == 95_00);
        Companies.tick(server, 2 + Config.payrollMillis()); check("payroll runs once per interval", () -> accounts.balance(b) == 105_00);
        check("/company info shows members and the account history", () -> {
            var info = output(alice.createCommandSourceStack(), "company info Acme");
            return info.contains("round3_bob (employee") && info.contains("company.wage");
        });

        as(bob, "shop open Acme Corner"); check("an employee cannot open a shop", () -> book.shop("Corner") == null);
        as(alice, "shop open Acme buy"); check("shop names cannot shadow /shop subcommands", () -> book.shop("buy") == null);
        as(alice, "shop open Acme Corner"); var corner = book.shop("Corner");
        check("a company opens a shop", () -> corner != null && corner.company == acme.id);
        as(alice, "shop offer Corner minecraft:bread 2 1 10");
        bob.getInventory().setItem(0, new ItemStack(Items.BREAD, 20));
        check("PDA restock refuses a forged or changed inventory item", () ->
                !pda.act(bob, "shop_stock", List.of("Corner", "minecraft:diamond", "12"), 0).ok()
                && items(bob, Items.BREAD) == 20 && corner.stock.isEmpty());
        check("PDA staff restock from the selected inventory slot", () ->
                pda.act(bob, "shop_stock", List.of("Corner", "minecraft:bread", "12"), 0).ok());
        check("staff stock a shop from their inventory", () -> corner.stock.get("minecraft:bread") == 12 && items(bob, Items.BREAD) == 8);
        var carl = player("round3_carl"); online(carl, true); Players.joined(carl); accounts.set(carl.getUUID(), 100_00);
        try {
            check("PDA directory selection does not leak into My Company for a nonmember", () -> {
                pda.act(carl, "tab", List.of("directory"), -1);
                boolean listed = pda.view(server, carl).has("company");
                pda.act(carl, "tab", List.of("mine"), -1);
                return listed && !pda.view(server, carl).has("company");
            });
            as(carl, "shop stock Corner minecraft:bread 1"); as(carl, "shop offer Corner minecraft:bread 1 1 1");
            check("customers cannot stock or reprice a shop", () -> corner.stock.get("minecraft:bread") == 12 && corner.offers.get("minecraft:bread").sellPrice() == 2_00);
            long before = accounts.balance(account), serverBefore = accounts.balance(Accounts.SERVER);
            check("PDA customer buys through existing shop accounting", () -> pda.act(carl, "shop_buy", List.of("Corner", "minecraft:bread", "5"), -1).ok());
            long tax = Taxes.percent(10_00, Config.shopTax(), 1);
            check("a purchase pays the company, taxes the sale and delivers the items", () -> accounts.balance(carl.getUUID()) == 90_00 && accounts.balance(account) == before + 10_00 - tax
                    && accounts.balance(Accounts.SERVER) == serverBefore + tax && items(carl, Items.BREAD) == 5 && corner.stock.get("minecraft:bread") == 7);
            as(carl, "shop buy Corner minecraft:bread 8"); check("a shop cannot sell more than its stock", () -> items(carl, Items.BREAD) == 5 && accounts.balance(carl.getUUID()) == 90_00);
            as(carl, "shop sell Corner minecraft:bread 3");
            check("a shop buys from customers with company money", () -> items(carl, Items.BREAD) == 2 && accounts.balance(carl.getUUID()) == 93_00
                    && corner.stock.get("minecraft:bread") == 10 && corner.offers.get("minecraft:bread").buyLimit() == 7);
            accounts.set(account, 0); as(carl, "shop sell Corner minecraft:bread 2");
            check("a shop that cannot pay buys nothing and takes nothing", () -> items(carl, Items.BREAD) == 2 && corner.stock.get("minecraft:bread") == 10);
            for (int i = 0; i < 36; i++) carl.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
            accounts.set(account, 100_00); as(carl, "shop buy Corner minecraft:bread 1");
            check("purchases with a full inventory wait in the collection box", () -> Market.store(server).value.goods.get(carl.getUUID()).get("minecraft:bread") == 1);
            check("/shops finds offers by item and by company", () -> output(carl.createCommandSourceStack(), "shops bread").contains("Corner")
                    && output(carl.createCommandSourceStack(), "shops acme").contains("Corner") && !output(carl.createCommandSourceStack(), "shops iron").contains("Corner"));
            as(carl, "shop Corner"); check("/shop <name> opens that shop", () -> carl.takeOpened() instanceof ModularUIContainerMenu && "Corner".equals(Companies.SELECTED.get(carl.getUUID())));

            as(alice, "shop admin Server"); check("only operators create admin shops", () -> book.shop("Server") == null);
            var server0 = book.open(a, null, "Server", 0); book.offer(server0, "minecraft:coal", 1_00, 50, 0);
            check("PDA refuses admin-shop editing and unknown admin verbs", () ->
                    !pda.act(alice, "shop_offer", List.of("Server", "minecraft:coal", "0", "0", "0"), -1).ok()
                    && !pda.act(alice, "shop_admin", List.of("Forbidden"), -1).ok()
                    && server0.offers.containsKey("minecraft:coal") && book.shop("Forbidden") == null);
            //? if >=26.1 {
            /*server.getPlayerList().op(alice.nameAndId());
            *///?} else {
            server.getPlayerList().op(alice.getGameProfile());
            //?}
            try {
                check("PDA keeps admin-shop editing out even for operators", () -> RankGate.isOperator(alice)
                        && !pda.act(alice, "shop_close", List.of("Server"), -1).ok() && book.shop("Server") == server0);
            } finally {
                //? if >=26.1 {
                /*server.getPlayerList().deop(alice.nameAndId());
                *///?} else {
                server.getPlayerList().deop(alice.getGameProfile());
                //?}
            }
            for (int i = 0; i < 36; i++) carl.getInventory().setItem(i, ItemStack.EMPTY);
            accounts.set(carl.getUUID(), 10_00); accounts.set(Accounts.SERVER, 0);
            as(carl, "shop buy Server minecraft:coal 4");
            check("an admin shop sells unlimited stock for the server", () -> items(carl, Items.COAL) == 4 && accounts.balance(carl.getUUID()) == 6_00 && accounts.balance(Accounts.SERVER) == 4_00);
            as(carl, "shop sell Server minecraft:coal 2");
            check("an admin shop buys with server money", () -> items(carl, Items.COAL) == 2 && accounts.balance(carl.getUUID()) == 7_00 && accounts.balance(Accounts.SERVER) == 3_00);
        } finally { online(carl, false); }

        var encoded = Companies.CODEC.encodeStart(JsonOps.INSTANCE, book).getOrThrow();
        check("the company codec preserves companies, roles, wages, shops and stock", () -> {
            var copy = Companies.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            var company = copy.company("Acme"); var shop = copy.shop("Corner");
            return company.members.equals(acme.members) && company.wages.equals(acme.wages) && company.owner.equals(a)
                    && shop.stock.equals(corner.stock) && shop.offers.equals(corner.offers) && copy.nextId == book.nextId;
        });
        as(bob, "company dissolve Acme"); check("only the owner can dissolve a company", () -> book.company("Acme") != null);
        long ownerBefore = accounts.balance(a), held = accounts.balance(account); long stocked = corner.stock.get("minecraft:bread");
        as(alice, "company dissolve Acme");
        check("dissolving pays the account to the owner and returns shop stock", () -> book.company("Acme") == null && book.shop("Corner") == null
                && accounts.balance(a) == ownerBefore + held && accounts.balance(account) == 0
                && Market.store(server).value.goods.get(a).get("minecraft:bread") == stocked);
        book.shops.clear();
        var help = output(alice.createCommandSourceStack(), "pluginless");
        check("company and shop commands are listed in /pluginless", () -> help.contains("/company") && help.contains("/shops") && help.contains("/shop "));
    }

    private void stocks(Tester alice, Tester bob, Accounts accounts) {
        var a = alice.getUUID();
        var configured = StockExchange.config;
        var config = new Stocks();
        var stock = new Stocks.Stock();
        stock.name = "Test Co"; stock.start = 10; stock.floor = 1; stock.ceiling = 100; stock.volatility = 0; stock.updateSeconds = 60;
        stock.spread = 2; stock.pressure = 1; stock.holdingLimit = 200; stock.headline = "{name} {move}";
        config.stocks.put("TEST", stock); config.dailyPayoutCap = 2500;
        StockExchange.config = config;
        var console = server.createCommandSourceStack().withSuppressedOutput();
        try {
            var state = StockExchange.state(server, "TEST"); state.price = 10; state.frozen = false; state.nudgeLeft = 0; state.trendUntil = 0; state.lastMove = 0;
            accounts.set(a, 10_000_00); accounts.set(Accounts.SERVER, 0);
            as(alice, "stock"); check("/stock opens the stock screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
            as(alice, "stock buy TEST 100");
            // 100 shares at 1% pressure per 100 moves 10.00 to 10.10; the buyer pays half the 2% spread on top.
            check("buying moves the price first and pays the server the worse price", () -> StockExchange.held(server, a, "TEST") == 100
                    && Math.abs(state.price - 10.10) < 1e-9 && accounts.balance(a) == 10_000_00 - 100 * 10_21 && accounts.balance(Accounts.SERVER) == 100 * 10_21);
            as(alice, "stock buy TEST 101"); check("the holding limit caps a position", () -> StockExchange.held(server, a, "TEST") == 100);
            as(alice, "stock buy NOPE 1"); check("unknown tickers are refused", () -> accounts.balance(a) == 10_000_00 - 100 * 10_21);
            as(alice, "stock sell TEST 100");
            check("an immediate round trip loses the spread and the pressure", () -> StockExchange.held(server, a, "TEST") == 0 && accounts.balance(a) < 10_000_00
                    && accounts.balance(a) + accounts.balance(Accounts.SERVER) == 10_000_00);
            as(alice, "stock sell TEST 1"); check("shares that are not held cannot be sold", () -> StockExchange.held(server, a, "TEST") == 0);

            as(alice, "stock nudge TEST 50"); check("players cannot nudge a stock", () -> state.nudgeLeft == 0);
            state.price = 10; server.getCommands().performPrefixedCommand(console, "stock nudge TEST 10");
            StockExchange.tick(server, state.lastUpdate + 60_000);
            check("an instant nudge lands on the next update", () -> Math.abs(state.price - 11) < 1e-9 && state.nudgeLeft == 0);
            state.price = 10; server.getCommands().performPrefixedCommand(console, "stock nudge TEST 10 4m");
            StockExchange.tick(server, state.lastUpdate + 60_000);
            check("a gradual nudge moves a part at a time", () -> state.price > 10 && state.price < 10.5 && state.nudgeLeft > 0);
            state.nudgeLeft = 0; state.price = 10;
            server.getCommands().performPrefixedCommand(console, "stock trend TEST down 1000 1h"); long start = state.lastUpdate;
            StockExchange.tick(server, start + 60_000);
            check("a trend pulls the price each update while it lasts", () -> Math.abs(state.price - 10 * (1 - 1000.0 / 1440 / 100)) < 1e-9);
            state.trendUntil = 0; server.getCommands().performPrefixedCommand(console, "stock set TEST 20");
            check("/stock set sets the price within the bounds", () -> state.price == 20);
            server.getCommands().performPrefixedCommand(console, "stock set TEST 5000"); check("a price outside the bounds is refused", () -> state.price == 20);
            server.getCommands().performPrefixedCommand(console, "stock shock TEST -50"); check("/stock shock moves the price at once", () -> Math.abs(state.price - 10) < 1e-9);
            server.getCommands().performPrefixedCommand(console, "stock freeze TEST"); as(alice, "stock buy TEST 1");
            StockExchange.tick(server, state.lastUpdate + 600_000);
            check("a frozen stock neither trades nor moves", () -> StockExchange.held(server, a, "TEST") == 0 && state.price == 10);
            server.getCommands().performPrefixedCommand(console, "stock unfreeze TEST");

            stock.pressure = 0; state.price = 10; accounts.set(a, 10_000_00); accounts.set(Accounts.SERVER, 0);
            as(alice, "stock buy TEST 200"); state.price = 20;
            SelfTest.<Boolean>configValue("SERVER_UNLIMITED").set(true);
            var saved = StockExchange.store(server).value; saved.paid = 0;
            as(alice, "stock sell TEST 100"); long afterFirst = accounts.balance(a);
            check("a winning sale is paid by the server, issuing money when it has none", () -> afterFirst == 10_000_00 - 200 * 10_10 + 100 * 19_80 && saved.paid == 100 * 19_80);
            as(alice, "stock sell TEST 100");
            check("the daily payout cap refuses further sales without moving anything", () -> accounts.balance(a) == afterFirst && StockExchange.held(server, a, "TEST") == 100 && state.price == 20);
            check("/stock list and /stock portfolio show price and holdings", () -> output(alice.createCommandSourceStack(), "stock list").contains("TEST")
                    && output(alice.createCommandSourceStack(), "stock portfolio").contains("100 TEST"));
            var encoded = StockExchange.CODEC.encodeStart(JsonOps.INSTANCE, saved).getOrThrow();
            check("the stock codec preserves prices, holdings and the payout count", () -> {
                var copy = StockExchange.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
                return copy.states.get("TEST").price == state.price && copy.holdings.equals(saved.holdings) && copy.paid == saved.paid && copy.history.equals(saved.history);
            });
            var file = FMLPaths.CONFIGDIR.get().resolve("pluginless/stocks.json");
            check("stocks.json is created with valid defaults", () -> Files.exists(file) && Stocks.defaults().problem() == null);

            // Margin: alice still holds 100 TEST; the sell quote is the price less the spread.
            state.price = 20; saved.paid = 0; accounts.set(a, 5_000_00); long worth = Stocks.quote(stock, state, false) * 100;
            as(alice, "margin borrow " + (worth / 2 / 100 + 1));
            check("a margin loan above half the holdings is refused", () -> Margin.loan(server, a) == 0 && accounts.balance(a) == 5_000_00);
            as(alice, "margin borrow 900");
            check("margin lends up to the multiple, paid by the server", () -> Margin.loan(server, a) == 900_00 && accounts.balance(a) == 5_900_00);
            as(alice, "stock sell TEST 100");
            check("stocks backing a margin loan cannot be sold away", () -> StockExchange.held(server, a, "TEST") == 100);
            state.price = 10.5; Margin.tick(server, System.currentTimeMillis());
            check("a warning does not sell anything", () -> StockExchange.held(server, a, "TEST") == 100 && Margin.loan(server, a) == 900_00);
            state.price = 9; Margin.tick(server, System.currentTimeMillis()); long proceeds = Stocks.quote(stock, state, false) * 100;
            check("below the liquidation ratio everything is sold and the shortfall becomes bank debt", () -> StockExchange.held(server, a, "TEST") == 0
                    && Margin.loan(server, a) == 0 && Bank.debt(server, a) == 900_00 - proceeds && accounts.balance(a) == 5_900_00);
            as(alice, "margin borrow 1"); check("a debtor cannot borrow on margin", () -> Margin.loan(server, a) == 0);
            Bank.repay(server, a, 1_000_00); StockExchange.store(server).value.holdings.computeIfAbsent(a, key -> new HashMap<>()).put("TEST", 100L);
            state.price = 20; as(alice, "margin borrow 900"); state.price = 9.9; long cash = accounts.balance(a);
            Margin.tick(server, System.currentTimeMillis()); long sold = Stocks.quote(stock, state, false) * 100;
            check("a forced sale that covers the loan pays the rest to the player", () -> Margin.loan(server, a) == 0 && Bank.debt(server, a) == 0 && accounts.balance(a) == cash + sold - 900_00);

            // A payout that fails after the sale keeps the loan and its proceeds, and the next tick finishes the job.
            accounts.set(Accounts.SERVER, 1_000_00); accounts.set(a, Long.MAX_VALUE - 10);
            Margin.loans(server).value.put(a, new MarginBook.Loan(new Debts.Debt(100_00, 100_00, System.currentTimeMillis()), false, java.util.Optional.of(150_00L)));
            Margin.tick(server, System.currentTimeMillis());
            check("a failed liquidation payout keeps the sold loan to retry", () -> Margin.loans(server).value.get(a) != null && Margin.loans(server).value.get(a).sold() && accounts.balance(a) == Long.MAX_VALUE - 10);
            as(alice, "margin borrow 1"); check("a loan waiting to settle cannot be borrowed on", () -> Margin.loans(server).value.get(a).debt().amount() == 100_00);
            accounts.set(a, 1_000_00); Margin.tick(server, System.currentTimeMillis());
            check("the retry pays the surplus and drops the loan", () -> Margin.loans(server).value.get(a) == null && accounts.balance(a) == 1_000_00 + 50_00);

            // Short selling: pressure is off, so the sell quote is 1% under the price and the buy quote 1% over.
            state.price = 10; accounts.set(a, 5_000_00); accounts.set(Accounts.ESCROW, 0); long entry = 100 * 9_90, deposit = entry / 2;
            as(alice, "short open TEST 100");
            check("a short sells at the sell quote and puts up the security in escrow", () -> Shorts.position(server, a, "TEST") != null && Shorts.position(server, a, "TEST").entry() == entry
                    && Shorts.position(server, a, "TEST").deposit() == deposit && accounts.balance(a) == 5_000_00 - deposit && accounts.balance(Accounts.ESCROW) == deposit);
            as(alice, "short open TEST 10"); check("only one short per stock", () -> Shorts.position(server, a, "TEST").shares() == 100);
            check("short interest is shown on the stock page", () -> output(alice.createCommandSourceStack(), "stock info TEST").contains("Sold short: 100"));
            state.price = 5; long gain = entry - 100 * 5_05;
            as(alice, "short close TEST");
            check("a short that falls in price pays the profit and returns the security", () -> Shorts.position(server, a, "TEST") == null && accounts.balance(a) == 5_000_00 + gain && accounts.balance(Accounts.ESCROW) == 0);
            state.price = 10; accounts.set(a, 5_000_00); as(alice, "short open TEST 100");
            Shorts.tick(server, System.currentTimeMillis() + 86_400_000L); long fee = Math.round(entry * 0.5 / 100);
            check("the daily fee is taken from the security", () -> Shorts.position(server, a, "TEST").deposit() == deposit - fee && accounts.balance(Accounts.ESCROW) == deposit - fee);
            server.getCommands().performPrefixedCommand(console, "stock freeze TEST"); state.price = 40; Shorts.tick(server, System.currentTimeMillis());
            check("a frozen stock cannot be covered, so a losing short waits", () -> Shorts.position(server, a, "TEST") != null);
            server.getCommands().performPrefixedCommand(console, "stock unfreeze TEST");
            long remaining = deposit - fee, loss = 100 * 40_40 - entry;
            Shorts.tick(server, System.currentTimeMillis());
            check("a short whose losses reach the security is closed and the shortfall becomes bank debt", () -> Shorts.position(server, a, "TEST") == null
                    && Bank.debt(server, a) == loss - remaining && accounts.balance(Accounts.ESCROW) == 0 && accounts.balance(a) == 5_000_00 - deposit);
            as(alice, "short open TEST 1"); check("a debtor cannot open a short", () -> Shorts.position(server, a, "TEST") == null);
            Bank.repay(server, a, Bank.debt(server, a));
            check("the short codec preserves a position", () -> {
                state.price = 10; as(alice, "short open TEST 5");
                var copy = Shorts.CODEC.parse(JsonOps.INSTANCE, Shorts.CODEC.encodeStart(JsonOps.INSTANCE, Shorts.all(server)).getOrThrow()).getOrThrow();
                return copy.equals(Shorts.all(server));
            });
        } finally {
            StockExchange.config = configured;
            StockExchange.store(server).value.holdings.remove(a);
            Shorts.all(server).remove(a);
        }
    }

    private void shares(Tester alice, Tester bob, Accounts accounts) {
        var a = alice.getUUID(); var b = bob.getUUID();
        var companies = Companies.store(server).value; var book = Shares.store(server).value;
        accounts.set(a, 10_000_00); accounts.set(b, 10_000_00); accounts.set(Accounts.SERVER, 0);
        as(alice, "company create Ponzi"); var ponzi = companies.company("Ponzi"); var account = CompanyBook.account(ponzi.id);
        as(alice, "shares"); check("/shares opens the share screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        as(bob, "shares issue Ponzi 100 0 10"); check("only the owner can issue shares", () -> book.outstanding(ponzi.id) == 0);
        as(alice, "shares issue Ponzi 100 40 10");
        check("issuing gives the owner the kept shares and offers the rest for the company", () -> book.held(ponzi.id, a) == 40
                && book.market.side(ShareBook.asset(ponzi.id), false).get(0).remaining() == 60);
        long before = accounts.balance(b); as(bob, "shares buy Ponzi 30 10");
        long fee = Taxes.percent(300_00, Config.marketFee(), 1);
        check("money from sold shares goes to the company account", () -> book.held(ponzi.id, b) == 30 && accounts.balance(b) == before - 300_00 && accounts.balance(account) == 300_00 - fee);
        long ownerBefore = accounts.balance(a); as(alice, "company withdraw Ponzi 100");
        check("the owner can withdraw what investors paid in", () -> accounts.balance(a) == ownerBefore + 100_00);
        long holderBefore = accounts.balance(b); as(alice, "shares dividend Ponzi 70");
        check("dividends are paid by shares from whatever is in the account", () -> accounts.balance(b) == holderBefore + 30_00 && accounts.balance(a) == ownerBefore + 100_00 + 40_00);
        check("the company page shows shareholders and /shares portfolio shows dividends", () -> output(bob.createCommandSourceStack(), "company info Ponzi").contains("round3_alice 40")
                && output(bob.createCommandSourceStack(), "shares portfolio").contains("30 of Ponzi"));
        as(bob, "shares takeover Ponzi"); check("a minority cannot take over", () -> ponzi.owner.equals(a));
        as(bob, "shares buy Ponzi 11 10"); as(bob, "shares takeover Ponzi");
        check("a majority shareholder takes the company", () -> ponzi.owner.equals(b) && !ponzi.can(a, "spend"));
        as(bob, "shares issue Ponzi 1000 1000 1");
        check("the new owner can issue shares to themselves and dilute everyone else", () -> book.votes(ponzi.id).get(b) == 1041 && book.outstanding(ponzi.id) == 1081);
        as(alice, "shares sell Ponzi 41 1"); check("shares that are not held cannot be sold", () -> book.held(ponzi.id, a) == 40);
        as(alice, "shares sell Ponzi 10 50"); long order = book.market.side(ShareBook.asset(ponzi.id), false).stream().filter(o -> o.owner().equals(a)).findFirst().orElseThrow().id();
        check("shares in a sell order still count as votes", () -> book.held(ponzi.id, a) == 30 && book.votes(ponzi.id).get(a) == 40);
        as(alice, "shares cancel " + order); check("cancelling a sell order returns the shares", () -> book.held(ponzi.id, a) == 40);

        var encoded = Shares.CODEC.encodeStart(JsonOps.INSTANCE, book).getOrThrow();
        check("the share codec preserves holdings and orders", () -> {
            var copy = Shares.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            return copy.holdings.equals(book.holdings) && copy.market.orders.equals(book.market.orders) && copy.lastIssue.equals(book.lastIssue);
        });
        as(bob, "company hire Ponzi round3_alice employee"); as(bob, "company wage Ponzi round3_alice 500");
        accounts.set(account, 108_10); accounts.set(a, 0); accounts.set(b, 0);
        // The item market checks left an open order of their own in escrow.
        long escrow = accounts.balance(Accounts.ESCROW);
        companies.lastPayroll = 1; Companies.tick(server, 10 + Config.payrollMillis());
        check("a company that misses a wage goes bankrupt and its account is split among shareholders", () -> companies.company("Ponzi") == null
                && book.outstanding(ponzi.id) == 0 && accounts.balance(a) == 4_00 && accounts.balance(b) == 104_10 && accounts.balance(Accounts.ESCROW) == escrow);
    }

    private void featureConfig() {
        var file = FMLPaths.CONFIGDIR.get().resolve("pluginless/features.json");
        check("features.json is created with defaults", () -> Files.exists(file));
        String original;
        try { original = Files.readString(file); } catch (IOException e) { throw new IllegalStateException(e); }
        var active = Features.settings;
        try {
            Files.writeString(file, "{broken");
            check("broken feature configuration is never applied", () -> Features.load() != null && Features.settings == active);
            check("broken feature configuration is preserved for repair", () -> {
                try { return Files.readString(file).equals("{broken"); } catch (IOException e) { return false; }
            });
        } catch (IOException e) { throw new IllegalStateException(e); }
        finally { try { Files.writeString(file, original); } catch (IOException e) { throw new IllegalStateException(e); } }
    }

    @SuppressWarnings("unchecked")
    private static <T> net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<T> configValue(String field) {
        try {
            var value = Config.class.getDeclaredField(field); value.setAccessible(true);
            return (net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<T>) value.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private void online(Tester player, boolean add) {
        try {
            var list = server.getPlayerList();
            var playersField = net.minecraft.server.players.PlayerList.class.getDeclaredField("players"); playersField.setAccessible(true);
            var uuidField = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID"); uuidField.setAccessible(true);
            var players = (List<ServerPlayer>) playersField.get(list);
            var byId = (Map<java.util.UUID, ServerPlayer>) uuidField.get(list);
            if (add) { players.add(player); byId.put(player.getUUID(), player); }
            else { players.remove(player); byId.remove(player.getUUID()); }
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
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
                    () -> RankGate.withPrefix(alice, alice.getName()).getString().equals("[Teller] selftest_alice")
                            && RankGate.withPrefix(alice, alice.getName()).getSiblings().get(0).getStyle().getColor().getValue() == 0x55ff55);

            server.getCommands().performPrefixedCommand(console, "rank set selftest_alice nonsense");
            check("/rank set refuses a rank that does not exist", () -> RankGate.rankOf(server, a).equals("teller"));
            rankFeatures(alice, accounts, ranks, normal, teller);
        } finally {
            RankGate.ranks = configured;
        }
    }

    private void groundwork(Tester alice, Accounts accounts) {
        var console = server.createCommandSourceStack().withSuppressedOutput();
        var never = UUIDUtil.createOfflinePlayerUUID("selftest_money");
        server.getCommands().performPrefixedCommand(console, "eco add selftest_money 5");
        check("/eco can fund a never-seen account from the console", () -> accounts.balance(never) == Config.startBalanceCents() + 5_00);
        int history = Ledger.history(server, alice.getUUID()).size();
        as(alice, "pay selftest_money 1");
        check("the transaction log records who, whom, amount and reason", () -> {
            var log = Ledger.history(server, alice.getUUID());
            var last = log.get(log.size() - 1);
            return log.size() == history + 1 && last.from().equals(alice.getUUID().toString()) && last.to().equals(never.toString())
                    && last.actor().equals(alice.getUUID().toString()) && last.amount() == 1_00 && last.reason().equals("pay:transfer");
        });
        int after = Ledger.history(server, alice.getUUID()).size();
        as(alice, "pay selftest_money 1000000");
        check("failed payments do not create transactions", () -> Ledger.history(server, alice.getUUID()).size() == after);
        server.getCommands().performPrefixedCommand(console, "eco set @server 10");
        check("the server account accepts fees and funds payouts", () -> Ledger.collect(server, alice.getUUID(), 1_00, "fee")
                && Ledger.payout(server, alice.getUUID(), 2_00, "shop") && accounts.balance(Accounts.SERVER) == 9_00
                && accounts.top(100).stream().noneMatch(e -> e.getKey().equals(Accounts.SERVER)));
        check("transaction codec preserves saved history", () -> {
            var tx = Ledger.history(server, Accounts.SERVER).get(0);
            var encoded = Ledger.Transaction.CODEC.encodeStart(JsonOps.INSTANCE, tx).getOrThrow();
            return Ledger.Transaction.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(tx);
        });
        check("shared formatting produces real RGB component styles", () -> {
            var text = Text.format("&cR &#123456H <gradient:#ff0000:#0000ff>AB</gradient>");
            return text.getString().equals("R H AB") && text.getSiblings().get(0).getStyle().getColor().getValue() == 0xff5555
                    && text.getSiblings().get(1).getStyle().getColor().getValue() == 0x123456;
        });
        var scheduler = new Scheduler();
        long[] elapsed = {0};
        scheduler.every(1000, 0, value -> elapsed[0] += value);
        scheduler.tick(999);
        check("scheduler waits for the deadline", () -> elapsed[0] == 0);
        scheduler.tick(3000);
        scheduler.tick(3000);
        check("scheduler runs an overdue task once with elapsed time", () -> elapsed[0] == 3000);
    }

    private void rankFeatures(Tester alice, Accounts accounts, Ranks ranks, Ranks.Rank normal, Ranks.Rank teller) {
        var a = alice.getUUID();
        var console = server.createCommandSourceStack().withSuppressedOutput();
        var never = UUIDUtil.createOfflinePlayerUUID("selftest_new");
        server.getCommands().performPrefixedCommand(console, "rank set Selftest_New teller");
        check("website console commands grant ranks before first join", () -> RankGate.rankOf(server, never).equals("teller"));
        var joining = new Tester(server.overworld(), new GameProfile(java.util.UUID.randomUUID(), "Selftest_New"));
        accounts.set(never, 7_00);
        Players.joined(joining);
        check("first login carries provisional ranks and balances to the joining UUID", () -> RankGate.rankOf(server, joining.getUUID()).equals("teller")
                && accounts.balance(joining.getUUID()) == 7_00 && !accounts.exists(never));

        ranks.cooldowns.put("pay", 30.0);
        long before = accounts.balance(a);
        output(alice.createCommandSourceStack(), "pluginless");
        as(alice, "pay selftest_bob 1");
        as(alice, "pay selftest_bob 1");
        check("cooldowns are consumed by execution, not help or permission checks", () -> accounts.balance(a) == before - 1_00);
        ranks.cooldowns.put("help", 30.0);
        String firstList = output(alice.createCommandSourceStack(), "help");
        String secondList = output(alice.createCommandSourceStack(), "help");
        check("the shared gate also cools down vanilla commands", () -> !firstList.contains("Wait") && secondList.contains("Wait"));
        teller.perks = new java.util.LinkedHashMap<>();
        teller.perks.put("cooldown.pay", 0);
        as(alice, "pay selftest_bob 1");
        check("rank cooldown perks take effect immediately", () -> accounts.balance(a) == before - 2_00);
        normal.perks = Map.of("income_multiplier", 1.5, "homes", 5, "nick", true);
        check("modules read inherited numeric limits and flags", () -> ranks.number("teller", "homes", 1) == 5 && ranks.flag("teller", "nick"));

        teller.nameColor = "<gradient:#ff0000:#0000ff>";
        teller.suffix = "&e!";
        teller.chatFormat = "[{prefix}{name}{suffix}] {message}";
        teller.messageColor = "&b";
        check("ranks format prefix, gradient names and suffix", () -> RankGate.withPrefix(alice, alice.getName()).getString().equals("[Teller] selftest_alice!"));
        check("per-rank chat controls brackets and message colour without parsing player markup", () ->
                RankGate.chat(alice, Component.literal("&c hello")).getString().equals("[[Teller] selftest_alice!] &c hello")
                        && RankGate.message(alice, Component.literal("hello")).getStyle().getColor().getValue() == 0x55ffff);
        as(alice, "rank gui");
        check("/rank gui opens the ROTH rank screen", () -> alice.takeOpened() instanceof ModularUIContainerMenu);
        check("a client cannot edit rank configuration without operator status", () -> RankUI.save(alice, ranks) != null);

        server.getCommands().performPrefixedCommand(console, "rank set selftest_alice default");
        long expiry = System.currentTimeMillis() + Durations.millis("30d") + 1000;
        server.getCommands().performPrefixedCommand(console, "rank set selftest_alice teller 30d");
        check("/rank set accepts a timed grant", () -> RankGate.rankOf(server, a).equals("teller") && RankGate.temporary(server, a));
        RankGate.expire(server, expiry);
        check("timed ranks revert to the previous rank", () -> RankGate.rankOf(server, a).equals("default") && !RankGate.temporary(server, a));
        check("timed rank codec preserves the previous rank and deadline", () -> {
            var lease = new RankGate.Timed("default", expiry);
            var encoded = RankGate.Timed.CODEC.encodeStart(JsonOps.INSTANCE, lease).getOrThrow();
            return RankGate.Timed.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(lease);
        });
        server.getCommands().performPrefixedCommand(console, "rank set selftest_alice teller 0d");
        check("invalid rank durations leave the assignment unchanged", () -> RankGate.rankOf(server, a).equals("default"));
        server.getCommands().performPrefixedCommand(console, "rank set selftest_alice teller 30d");
        server.getCommands().performPrefixedCommand(console, "rank set selftest_alice teller");
        RankGate.expire(server, expiry);
        check("a permanent grant cancels an old expiry", () -> RankGate.rankOf(server, a).equals("teller"));

        var kit = new Ranks.Kit();
        kit.money = 2;
        kit.items = Map.of("minecraft:diamond", 2);
        kit.cooldown = 3600;
        ranks.kits.put("test", kit);
        teller.perks.put("kit.test", true);
        long kitBefore = accounts.balance(a);
        int diamonds = items(alice, Items.DIAMOND);
        as(alice, "kit test");
        check("/kit gives configured items and rank-scaled money", () -> accounts.balance(a) == kitBefore + 3_00 && items(alice, Items.DIAMOND) == diamonds + 2);
        as(alice, "kit test");
        check("kit cooldowns reject a second claim", () -> accounts.balance(a) == kitBefore + 3_00 && items(alice, Items.DIAMOND) == diamonds + 2);
        teller.perks.put("cooldown.kit.test", 0);
        as(alice, "kit test");
        check("kit cooldown perks override the shared kit mechanism", () -> accounts.balance(a) == kitBefore + 6_00);
        var blocked = player("selftest_full");
        RankGate.setRank(server, blocked.getUUID(), "teller");
        for (int i = 0; i < 36; i++) blocked.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        long fullBefore = accounts.balance(blocked.getUUID());
        as(blocked, "kit test");
        check("full inventories receive neither kit money nor a claimed cooldown", () -> accounts.balance(blocked.getUUID()) == fullBefore
                && !RankRewards.of(server, blocked.getUUID()).kits().containsKey("test"));
        var denied = player("selftest_denied");
        long deniedBefore = accounts.balance(denied.getUUID());
        as(denied, "kit test");
        check("kits are gated by rank", () -> accounts.balance(denied.getUUID()) == deniedBefore && items(denied, Items.DIAMOND) == 0);

        var reward = new Ranks.Reward();
        reward.id = "first";
        reward.hours = 1.0 / 3600;
        reward.money = 1;
        reward.items = Map.of("minecraft:bread", 1);
        teller.rewards = List.of(reward);
        check("playtime rewards are visible by command and in the rank screen", () -> output(alice.createCommandSourceStack(), "rank rewards").contains("first")
                && RankUI.rewards(alice, "teller").getString().contains("first"));
        long rewardBefore = accounts.balance(a);
        RankRewards.online(alice, 1);
        RankRewards.reward(alice);
        check("once-only playtime rewards use inherited income multipliers", () -> accounts.balance(a) == rewardBefore + 1_50
                && RankRewards.of(server, a).rewards().get("first") == 1);
        reward.repeat = true;
        RankRewards.online(alice, 1);
        check("repeating playtime rewards pay each elapsed interval once", () -> accounts.balance(a) == rewardBefore + 3_00);
        check("reward and kit codecs preserve saved state", () -> {
            var state = RankRewards.of(server, a);
            var encoded = RankRewards.Progress.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
            return RankRewards.Progress.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow().equals(state);
        });

        var track = new Ranks.Track();
        track.ranks = List.of("default", "teller");
        ranks.tracks.put("main", track);
        server.getCommands().performPrefixedCommand(console, "rank demote selftest_alice main");
        check("/rank demote steps down a track", () -> RankGate.rankOf(server, a).equals("default"));
        server.getCommands().performPrefixedCommand(console, "rank promote selftest_alice main");
        check("/rank promote steps up a track", () -> RankGate.rankOf(server, a).equals("teller"));
        server.getCommands().performPrefixedCommand(console, "rank promote selftest_alice main");
        check("promotion refuses the end of a track", () -> RankGate.rankOf(server, a).equals("teller"));
        server.getCommands().performPrefixedCommand(console, "rank demote selftest_alice main");
        track.prices = Map.of("teller", 2.0);
        normal.perks = new java.util.LinkedHashMap<>(normal.perks);
        normal.perks.put("fee_multiplier", 0.5);
        long paidBefore = accounts.balance(a);
        long serverBefore = accounts.balance(Accounts.SERVER);
        as(alice, "rank promote selftest_alice main");
        check("paid promotions apply rank fee perks and fund the server account", () -> RankGate.rankOf(server, a).equals("teller")
                && accounts.balance(a) == paidBefore - 1_00 && accounts.balance(Accounts.SERVER) == serverBefore + 1_00);
        server.getCommands().performPrefixedCommand(console, "rank demote selftest_alice main");
        track.autoHours = Map.of("teller", 3.0 / 3600);
        RankRewards.online(alice, 1);
        check("tracks can auto-promote on online playtime", () -> RankGate.rankOf(server, a).equals("teller"));

        configEdits(alice, ranks);
    }

    private void configEdits(Tester alice, Ranks ranks) {
        var file = FMLPaths.CONFIGDIR.get().resolve("pluginless/ranks.json");
        String original;
        try { original = Files.readString(file); } catch (IOException e) { throw new IllegalStateException(e); }
        try {
            //? if >=26.1 {
            /*server.getPlayerList().op(alice.nameAndId());
            *///?} else {
            server.getPlayerList().op(alice.getGameProfile());
            //?}
            var draft = new Gson().fromJson(new Gson().toJson(ranks), Ranks.class);
            draft.ranks.get("teller").allow = List.of("eco", "baltop");
            draft.ranks.get("teller").perks.put("homes", 9);
            check("operators can persist rank rules and perks from the GUI", () -> RankUI.save(alice, draft) == null
                    && RankGate.ranks.number("teller", "homes", 1) == 9);
            check("GUI edits survive config reload", () -> RankGate.load() == null && RankGate.ranks.ranks.get("teller").allow.contains("baltop"));
            Files.writeString(file, "{broken");
            var active = RankGate.ranks;
            check("broken rank files are neither applied nor overwritten", () -> RankGate.load() != null && RankGate.ranks == active
                    && RankGate.save(draft) != null);
            check("failed GUI edits preserve a broken file for repair", () -> {
                try { return Files.readString(file).equals("{broken"); } catch (IOException e) { return false; }
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            //? if >=26.1 {
            /*server.getPlayerList().deop(alice.nameAndId());
            *///?} else {
            server.getPlayerList().deop(alice.getGameProfile());
            //?}
            try { Files.writeString(file, original); } catch (IOException e) { throw new IllegalStateException(e); }
        }
    }

    private static int items(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            var stack = player.getInventory().getItem(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
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

        // FakePlayer's network teleport is a no-op; keep the real server call and apply its successful position.
        //? if >=26.1 {
        /*@Override
        public boolean teleportTo(ServerLevel level, double x, double y, double z, java.util.Set<net.minecraft.world.entity.Relative> relatives, float yaw, float pitch, boolean resetCamera) {
            boolean ok = super.teleportTo(level, x, y, z, relatives, yaw, pitch, resetCamera);
            if (ok) setPos(x, y, z);
            return ok;
        }
        *///?} else {
        @Override
        public boolean teleportTo(ServerLevel level, double x, double y, double z, java.util.Set<net.minecraft.world.entity.RelativeMovement> relatives, float yaw, float pitch) {
            boolean ok = super.teleportTo(level, x, y, z, relatives, yaw, pitch);
            if (ok) setPos(x, y, z);
            return ok;
        }
        //?}

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
