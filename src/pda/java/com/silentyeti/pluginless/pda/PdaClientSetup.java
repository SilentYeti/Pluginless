package com.silentyeti.pluginless.pda;

import com.wf.wflib.pda.client.PdaApps;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** Client entry: registers the Pluginless app on the WFLib PDA and sends the page traffic. */
public final class PdaClientSetup {
    public static final float AREA_WIDTH = 384;
    /** App area below the top bar. */
    public static final float AREA_HEIGHT = 222 - PlApp.TOP;

    private PdaClientSetup() {}

    static void init(IEventBus modBus) {
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(() -> {
            PlApp.register();
            PdaApps.register(PlApp.INSTANCE);
        }));
    }

    /** Pages in tile order; a card adds its page here. The home page is always first. */
    static List<PdaClientPage> pages() {
        return List.of(new HomeClientPage(), new QolClientPage());
    }

    /** True when the connected server has this mod's PDA channels. */
    static boolean linked() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(PdaNet.Open.TYPE);
    }

    public static void open(String page) {
        PdaClientState.page(page);
        PacketDistributor.sendToServer(new PdaNet.Open(page));
    }

    public static void close() {
        PacketDistributor.sendToServer(new PdaNet.Close());
    }

    /** One intent on the open page. {@code slot}: an inventory slot the player picked, or -1. */
    public static void send(String page, String verb, List<String> args, int slot) {
        PacketDistributor.sendToServer(new PdaNet.Action(page, verb, args, slot));
    }

    public static void send(String page, String verb, String... args) {
        send(page, verb, List.of(args), -1);
    }
}
