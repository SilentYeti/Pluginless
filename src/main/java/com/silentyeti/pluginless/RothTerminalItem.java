package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
//? if >=26.1 {
/*import net.minecraft.world.InteractionResult;
*///?} else {
import net.minecraft.world.InteractionResultHolder;
//?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class RothTerminalItem extends Item implements HeldItemUIMenuType.HeldItemUI {
    public RothTerminalItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    //? if >=26.1 {
    /*@Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            HeldItemUIMenuType.openUI(serverPlayer, hand);
        }
        return InteractionResult.SUCCESS;
    }
    *///?} else {
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            HeldItemUIMenuType.openUI(serverPlayer, hand);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
    //?}

    @Override
    public ModularUI createUI(HeldItemUIMenuType.HeldItemUIHolder holder) {
        return TerminalUI.create(holder.player);
    }
}
