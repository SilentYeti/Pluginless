package dev.silentyeti.pdaharness;

import com.google.gson.JsonObject;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.terminal.PdaTerminal;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Actual dedicated-server inventory mutations and assertions, never a client inventory simulation. */
@EventBusSubscriber(modid = "pdaharness")
public final class CarryServer {
    private static List<ItemStack> expected;
    private static String token;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        var file = Harness.CONTROL.resolve("carry-server.json");
        if (!Files.exists(file)) return;
        int id = -1;
        try {
            JsonObject command = Harness.JSON.fromJson(Files.readString(file), JsonObject.class);
            Files.delete(file);
            id = command.get("id").getAsInt();
            var player = event.getServer().getPlayerList().getPlayerByName("PdaTester");
            if (player == null) throw new IllegalStateException("No PdaTester");
            String op = command.get("op").getAsString();
            if (op.equals("carry_kill")) {
                player.kill();
                Harness.write("result-" + id + ".json", Map.of("ok", true, "alive", player.isAlive()));
                return;
            }
            if (op.equals("carry_move")) {
                ItemStack pda = CarrySlots.pda(player);
                if (pda.isEmpty()) pda = new ItemStack(com.wf.wflib.item.ModItems.PDA.get());
                if (token == null) token = UUID.randomUUID().toString();
                CompoundTag data = new CompoundTag();
                data.putString("carry_proof", token);
                pda.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                for (int i = 0; i < player.getInventory().getContainerSize(); i++)
                    if (Pda.is(player.getInventory().getItem(i))) player.getInventory().setItem(i, ItemStack.EMPTY);
                var equipped = CarrySlots.equipped(player);
                if (equipped != null) for (int i = 0; i < equipped.getSlots(); i++)
                    if (Pda.is(equipped.getStackInSlot(i))) equipped.setStackInSlot(i, ItemStack.EMPTY);
                player.getInventory().selected = 0;
                player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
                player.getInventory().setItem(40, new ItemStack(Items.SHIELD));
                String target = command.get("target").getAsString();
                if (target.startsWith("curios:")) {
                    var handler = CarrySlots.slot(player, target.substring(7));
                    // Validate the live data tag and real slot API, rather than assuming a tag grants slots.
                    Object context = Class.forName("top.theillusivec4.curios.api.SlotContext")
                            .getConstructor(String.class, net.minecraft.world.entity.LivingEntity.class,
                                    int.class, boolean.class, boolean.class)
                            .newInstance(target.substring(7), player, 0, false, true);
                    boolean valid = (boolean) Class.forName("top.theillusivec4.curios.api.CuriosApi")
                            .getMethod("isStackValid", context.getClass(), ItemStack.class).invoke(null, context, pda);
                    if (!valid) throw new IllegalStateException("PDA rejected by real Curios slot " + target);
                    handler.setStackInSlot(0, pda);
                } else if (!target.equals("none")) {
                    player.getInventory().setItem(target.equals("offhand") ? 40 : Integer.parseInt(target), pda);
                }
                player.inventoryMenu.broadcastChanges();
                expected = CarrySlots.snapshot(player);
            } else if (!op.equals("carry_server_assert")) throw new IllegalArgumentException(op);
            var now = CarrySlots.snapshot(player);
            if (expected == null || now.size() != expected.size()) throw new IllegalStateException("No matching inventory baseline");
            for (int i = 0; i < now.size(); i++) if (!ItemStack.matches(now.get(i), expected.get(i)))
                throw new IllegalStateException("Server item/count/components moved at index " + i);
            int count = CarrySlots.count(player);
            if (count > 1) throw new IllegalStateException("Duplicated PDA");
            boolean carried = Pda.carries(player);
            boolean terminal = !PdaTerminal.run(player, "help").getFirst().startsWith("err:");
            if (carried != (count == 1) || terminal != carried) throw new IllegalStateException("Server carry/terminal disagreement");
            Harness.write("result-" + id + ".json", Map.of("ok", true, "carried", carried, "count", count,
                    "physical_held", Pda.held(player), "terminal_access", terminal, "token", token,
                    "inventory_unchanged", true));
        } catch (Exception e) {
            Harness.write("result-" + id + ".json", Map.of("ok", false, "error", e.toString()));
        }
    }
}
