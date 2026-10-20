package com.silentyeti.pluginless;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
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

    public static final ResourceLocation RANK_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "ranks");
    public static final ResourceLocation BALANCE_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "balance");
    public static final ResourceLocation BANK_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "bank");
    public static final ResourceLocation COSMETICS_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "cosmetics");
    public static final ResourceLocation QOL_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "qol");
    public static final ResourceLocation AUCTION_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "auction");
    public static final ResourceLocation MARKET_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "market");
    public static final ResourceLocation COMPANY_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "company");
    public static final ResourceLocation SHOP_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "shops");
    public static final ResourceLocation STOCK_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "stocks");
    public static final ResourceLocation SHARES_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "shares");
    public static final ResourceLocation TAXES_UI = ResourceLocation.fromNamespaceAndPath(MOD_ID, "taxes");
    private Scheduler scheduler;

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
        PlayerUIMenuType.register(RANK_UI, player -> RankUI::create);
        PlayerUIMenuType.register(BALANCE_UI, player -> TerminalUI::createBalance);
        PlayerUIMenuType.register(BANK_UI, player -> BankUI::create);
        PlayerUIMenuType.register(COSMETICS_UI, player -> CosmeticsUI::create);
        PlayerUIMenuType.register(QOL_UI, player -> QolUI::create);
        PlayerUIMenuType.register(AUCTION_UI, player -> AuctionUI::create);
        PlayerUIMenuType.register(TAXES_UI, player -> AuctionUI::taxes);
        PlayerUIMenuType.register(MARKET_UI, player -> MarketUI::create);
        PlayerUIMenuType.register(STOCK_UI, player -> StockUI::create);
        PlayerUIMenuType.register(SHARES_UI, player -> CompanyUI::shares);
        PlayerUIMenuType.register(COMPANY_UI, player -> CompanyUI::create);
        PlayerUIMenuType.register(SHOP_UI, player -> CompanyUI::shops);

        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(ROTH_TERMINAL);
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> Commands.register(event.getDispatcher()));
        // LOWEST: the rank rules must wrap the commands of every mod, so this runs after they registered.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RegisterCommandsEvent event) -> RankGate.install(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> {
            var problem = RankGate.load();
            if (problem != null) LOGGER.error("ranks.json was not loaded, keeping the previous ranks: {}", problem);
            problem = Features.load();
            if (problem != null) LOGGER.error("features.json was not loaded, keeping the previous settings: {}", problem);
            problem = StockExchange.load();
            if (problem != null) LOGGER.error("stocks.json was not loaded, keeping the previous stocks: {}", problem);
        });
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            scheduler = new Scheduler();
            ClaimCompat.init(event.getServer());
            long now = System.nanoTime() / 1_000_000;
            scheduler.every(1000, now, elapsed -> RankGate.expire(event.getServer(), System.currentTimeMillis()));
            scheduler.every(1000, now, elapsed -> Bank.tick(event.getServer(), System.currentTimeMillis()));
            scheduler.every(1000, now, elapsed -> Auctions.tick(event.getServer(), System.currentTimeMillis()));
            scheduler.every(60_000, now, elapsed -> Companies.tick(event.getServer(), System.currentTimeMillis()));
            scheduler.every(1000, now, elapsed -> StockExchange.tick(event.getServer(), System.currentTimeMillis()));
            scheduler.every(1000, now, elapsed -> Margin.tick(event.getServer(), System.currentTimeMillis()));
            long[] remainder = {0};
            scheduler.every(1000, now, elapsed -> {
                long total = elapsed + remainder[0];
                remainder[0] = total % 1000;
                event.getServer().getPlayerList().getPlayers().forEach(player -> { RankRewards.online(player, total / 1000); Income.online(player, total / 1000); });
            });
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            Teleports.tick(event.getServer(), System.currentTimeMillis());
            if (scheduler != null) scheduler.tick(System.nanoTime() / 1_000_000);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            scheduler = null; ClaimCompat.clear(); Teleports.clear(event.getServer()); Bank.clear(event.getServer()); Income.clear(); Companies.SELECTED.clear();
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) -> {
            if (event.getOriginalDamage() > event.getBlockedDamage() && event.getEntity() instanceof ServerPlayer player) Teleports.cancel(player);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) -> {
            if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player) { Teleports.cancel(player); Teleports.remember(player); }
            else if (!event.isCanceled() && event.getSource().getEntity() instanceof ServerPlayer killer)
                Income.kill(killer, net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString());
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (net.neoforged.neoforge.event.entity.EntityTeleportEvent event) -> {
            if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player) Teleports.external(player, arrived ->
                    Math.abs(arrived.getX() - event.getTargetX()) < 0.01 && Math.abs(arrived.getY() - event.getTargetY()) < 0.01 && Math.abs(arrived.getZ() - event.getTargetZ()) < 0.01);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent event) -> {
            if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player && !player.level().dimension().equals(event.getDimension()))
                Teleports.external(player, arrived -> arrived.level().dimension().equals(event.getDimension()));
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                Teleports.leave(player);
                var server = player.level().getServer();
                if (server != null) Bank.requests(server).remove(player.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerChatEvent event) -> {
            var formatted = RankGate.chat(event.getPlayer(), event.getMessage());
            if (formatted != null) {
                event.setCanceled(true);
                event.getPlayer().level().getServer().getPlayerList().broadcastSystemMessage(formatted, false);
            } else event.setMessage(RankGate.message(event.getPlayer(), event.getMessage()));
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.NameFormat event) -> {
            if (event.getEntity() instanceof ServerPlayer player) event.setDisplayname(RankGate.withPrefix(player, event.getDisplayname()));
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.TabListNameFormat event) -> {
            if (event.getEntity() instanceof ServerPlayer player) event.setDisplayName(RankGate.withPrefix(player, player.getName()));
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) { Players.joined(player); Income.login(player); Auctions.deliver(player.level().getServer()); }
        });
        if (Boolean.getBoolean("pluginless.selftest")) {
            NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> SelfTest.run(event.getServer()));
        }
    }
}
