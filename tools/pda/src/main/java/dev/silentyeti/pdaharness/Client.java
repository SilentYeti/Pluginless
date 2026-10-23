package dev.silentyeti.pdaharness;

import com.google.gson.JsonObject;
import com.wf.wflib.pda.client.PdaApps;
import com.wf.wflib.pda.client.PdaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.util.Map;

@EventBusSubscriber(modid = "pdaharness", value = Dist.CLIENT)
public final class Client {
    private static boolean ready;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!ready && mc.screen == null && mc.player.tickCount > 40 && PdaClient.held()) {
            ready = true;
            Harness.write("client-ready.json", Map.of("held", true, "pid", ProcessHandle.current().pid()));
        }
        var action = Harness.CONTROL.resolve("action.json");
        if (!Files.exists(action)) return;
        int id = -1;
        try {
            var command = Harness.JSON.fromJson(Files.readString(action), JsonObject.class);
            Files.delete(action);
            id = command.get("id").getAsInt();
            switch (command.get("op").getAsString()) {
                case "open" -> {
                    var appId = ResourceLocation.parse(command.get("app").getAsString());
                    var app = PdaApps.get(appId);
                    if (app == null || !app.visible(mc.player)) throw new IllegalArgumentException("Missing/invisible PDA app: " + appId);
                    PdaClient.open(app);
                }
                case "home" -> PdaClient.home();
                case "pointer" -> {
                    float x = command.get("x").getAsFloat(), y = command.get("y").getAsFloat();
                    if (!(x >= 0 && x < 384 && y >= 0 && y < 240)) throw new IllegalArgumentException("Pointer outside PDA");
                    // shortcut: WFLib 1.8.36's cursor gain is 0.55; update when its steer contract changes.
                    PdaClient.steer((x - PdaClient.cursorX()) / 0.55, (y - PdaClient.cursorY()) / 0.55);
                }
                case "assert_text" -> {
                    var focused = com.wf.wflib.ui.input.UiKeyboard.focused();
                    if (!(focused instanceof com.wf.wflib.ui.input.TextLine line)
                            || !line.text().toString().equals(command.get("text").getAsString())) {
                        throw new IllegalStateException("PDA text field does not contain expected text");
                    }
                }
                case "screenshot" -> {
                    int receipt = id;
                    Screenshot.grab(mc.gameDirectory, "capture-" + id + ".png", mc.getMainRenderTarget(),
                            message -> mc.execute(() -> Harness.write("result-" + receipt + ".json", Map.of("ok", true, "message", message.getString()))));
                    return;
                }
                case "state" -> { }
                default -> throw new IllegalArgumentException("Unknown harness action");
            }
            Harness.write("result-" + id + ".json", Map.of("ok", true, "held", PdaClient.held(), "focused", PdaClient.focused(),
                    "app", PdaClient.app() == null ? "home" : PdaClient.app().id().toString()));
        } catch (Exception e) {
            Harness.write("result-" + id + ".json", Map.of("ok", false, "error", e.toString()));
        }
    }
}
