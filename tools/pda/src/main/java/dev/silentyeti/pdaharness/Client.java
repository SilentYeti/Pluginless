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
                case "assert_layout" -> {
                    if (!PdaClient.focused() || PdaClient.app() == null)
                        throw new IllegalStateException("Layout audit requires a focused PDA app");
                    LayoutAudit.verifyGuard();
                    int labels = LayoutAudit.check(PdaClient.app().build());
                    Harness.write("result-" + id + ".json", Map.of("ok", true, "labels_checked", labels));
                    return;
                }
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
                case "assert_page" -> {
                    if (!PdaClient.focused() || PdaClient.app() == null
                            || !PdaClient.app().id().toString().equals("pluginless:pluginless")) {
                        throw new IllegalStateException("Pluginless PDA app is not focused");
                    }
                    // The harness also runs against jars without PDA integration; keep that dependency optional.
                    var state = Class.forName("com.silentyeti.pluginless.pda.PdaClientState", true,
                            PdaClient.app().getClass().getClassLoader());
                    String page = (String) state.getMethod("page").invoke(null);
                    if (!page.equals(command.get("page").getAsString())) {
                        throw new IllegalStateException("Expected page " + command.get("page").getAsString() + ", got " + page);
                    }
                    var view = (JsonObject) state.getMethod("view", String.class).invoke(null, page);
                    if (!view.has("balance")) throw new IllegalStateException("No server snapshot for page " + page);
                    if (command.has("values")) for (var expected : command.getAsJsonObject("values").entrySet()) {
                        if (!expected.getValue().equals(view.get(expected.getKey())))
                            throw new IllegalStateException("Snapshot " + expected.getKey() + ": expected "
                                    + expected.getValue() + ", got " + view.get(expected.getKey()));
                    }
                    Harness.write("result-" + id + ".json", Map.of("ok", true, "page", page, "view", view));
                    return;
                }
                case "assert_inventory" -> {
                    String itemId = command.get("item").getAsString();
                    int count = 0;
                    boolean ownProfile = false;
                    for (int slot = 0; slot < 36; slot++) {
                        var stack = mc.player.getInventory().getItem(slot);
                        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) continue;
                        count += stack.getCount();
                        var profile = stack.get(net.minecraft.core.component.DataComponents.PROFILE);
                        if (profile != null && profile.gameProfile().getName().equals(mc.player.getGameProfile().getName())) ownProfile = true;
                    }
                    if (count != command.get("count").getAsInt() || command.has("own_profile") && command.get("own_profile").getAsBoolean() && !ownProfile)
                        throw new IllegalStateException("Inventory mismatch: " + itemId + " count=" + count + " ownProfile=" + ownProfile);
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
