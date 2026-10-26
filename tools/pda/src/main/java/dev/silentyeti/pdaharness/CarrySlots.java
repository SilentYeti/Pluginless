package dev.silentyeti.pdaharness;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.Optional;

/** Dev-only optional API bridge: no Curios classes are resolved on absent-mod runs. */
final class CarrySlots {
    static ItemStack pda(Player player) throws Exception {
        return (ItemStack) com.wf.wflib.pda.Pda.class.getMethod("carriedStack", Player.class).invoke(null, player);
    }

    static IItemHandlerModifiable slot(Player player, String name) throws Exception {
        if (!ModList.get().isLoaded("curios")) throw new IllegalStateException("Curios is not installed");
        Object inventory = ((Optional<?>) Class.forName("top.theillusivec4.curios.api.CuriosApi")
                .getMethod("getCuriosInventory", LivingEntity.class).invoke(null, player)).orElseThrow();
        Object handler = ((Optional<?>) Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler")
                .getMethod("getStacksHandler", String.class).invoke(inventory, name)).orElseThrow();
        return (IItemHandlerModifiable) Class.forName("top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler")
                .getMethod("getStacks").invoke(handler);
    }

    static IItemHandlerModifiable equipped(Player player) throws Exception {
        if (!ModList.get().isLoaded("curios")) return null;
        Object inventory = ((Optional<?>) Class.forName("top.theillusivec4.curios.api.CuriosApi")
                .getMethod("getCuriosInventory", LivingEntity.class).invoke(null, player)).orElseThrow();
        return (IItemHandlerModifiable) Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler")
                .getMethod("getEquippedCurios").invoke(inventory);
    }

    static java.util.List<ItemStack> snapshot(Player player) throws Exception {
        var stacks = new java.util.ArrayList<ItemStack>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) stacks.add(player.getInventory().getItem(i).copy());
        var equipped = equipped(player);
        if (equipped != null) for (int i = 0; i < equipped.getSlots(); i++) stacks.add(equipped.getStackInSlot(i).copy());
        return stacks;
    }

    static int count(Player player) throws Exception {
        return snapshot(player).stream().filter(com.wf.wflib.pda.Pda::is).mapToInt(ItemStack::getCount).sum();
    }
}
