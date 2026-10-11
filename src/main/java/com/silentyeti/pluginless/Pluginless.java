package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(Pluginless.MOD_ID)
public final class Pluginless {
    public static final String MOD_ID = "pluginless";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation TERMINAL_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "terminal");

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredItem<RothTerminalItem> ROTH_TERMINAL =
            ITEMS.registerItem("roth_terminal", RothTerminalItem::new);

    public Pluginless(IEventBus modBus, ModContainer container) {
        ITEMS.register(modBus);
        // One file name on every version: the default suffix follows the type, which 26.3 renamed.
        //? if >=26.3 {
        /*container.registerConfig(ModConfig.Type.LOCAL, Config.SPEC, MOD_ID + ".toml");
        *///?} else {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC, MOD_ID + ".toml");
        //?}
        // Both sides look this up, so it is registered here and not on server start.
        PlayerUIMenuType.register(TERMINAL_UI, player -> TerminalUI::create);

        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(ROTH_TERMINAL);
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> Commands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            var server = event.getEntity().level().getServer();
            if (server != null) Ledger.of(server).open(event.getEntity().getUUID());
        });
        if (Boolean.getBoolean("pluginless.selftest")) {
            NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> SelfTest.run(event.getServer()));
        }
    }
}
