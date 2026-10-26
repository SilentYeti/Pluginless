package dev.silentyeti.pdaharness;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.silentyeti.pluginless.Accounts;
import com.silentyeti.pluginless.Ledger;
import com.wf.wflib.item.ModItems;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Mod("pdaharness")
@EventBusSubscriber(modid = "pdaharness")
public final class Harness {
    static final Path CONTROL = Path.of(System.getProperty("pda.harness.control"));
    static final Gson JSON = new Gson();

    static void write(String name, Object value) {
        try {
            Files.writeString(CONTROL.resolve(name + ".tmp"), JSON.toJson(value));
            Files.move(CONTROL.resolve(name + ".tmp"), CONTROL.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { throw new IllegalStateException("Harness output failed: " + name, e); }
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        var server = event.getServer();
        var accounts = Ledger.of(server);
        if (!accounts.set(Accounts.SERVER, 100_000_00L)) throw new IllegalStateException("Server funding failed");
        String name = "harness_seller";
        var id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        var seller = FakePlayerFactory.get(server.overworld(), new GameProfile(id, name));
        if (!accounts.set(id, 10_000_00L)) throw new IllegalStateException("Seller funding failed");
        seller.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 8));
        server.getCommands().performPrefixedCommand(seller.createCommandSourceStack().withPermission(4), "ah sell 25");
        if (!seller.getMainHandItem().isEmpty()) throw new IllegalStateException("Auction seed command failed");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "stock freeze YETI");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "stock freeze GOLD");
        write("server-ready.json", Map.of("seller", id.toString(), "listing", "8 diamonds at 25", "stocks", "YETI, GOLD frozen"));
    }

    @SubscribeEvent
    public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        if (!player.getGameProfile().getName().equals("PdaTester")) return;
        if (!Ledger.of(player.server).set(player.getUUID(), 25_000_00L)) throw new IllegalStateException("Player funding failed");
        player.setGameMode(GameType.CREATIVE);
        player.teleportTo(0.5, -59, 0.5);
        player.setYRot(0);
        player.setXRot(0);
        player.getInventory().selected = 0;
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PDA.get()));
        if (Boolean.getBoolean("pda.harness.longText")) {
            String company = "WWWW_MMMM_WWWW_MMMM_WWWW"; // Legal maximum: 24 characters.
            String home = "WWWW_MMMM_WWWW_MMMM_WWWW_MMMM_WW"; // Legal maximum: 32.
            var source = player.createCommandSourceStack().withPermission(4);
            for (String command : new String[]{"company create " + company,
                    "company describe " + company + " " + "Wide company description with WWWW MMMM and a complete final sentence that must survive template substitution.",
                    "sethome " + home}) {
                try {
                    if (player.server.getCommands().getDispatcher().execute(command, source) <= 0)
                        throw new IllegalStateException("Long-text seed failed: " + command);
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    throw new IllegalStateException("Long-text seed syntax failed: " + command, e);
                }
            }
            write("long-text-seed.json", Map.of("company", company, "home", home));
        }
        write("player-ready.json", Map.of("name", "PdaTester", "balanceCents", 25_000_00L));
    }
}
