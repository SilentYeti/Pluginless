package dev.silentyeti.pdaharness;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.client.PdaClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.file.Files;
import java.util.HashMap;

@EventBusSubscriber(modid = "pdaharness", value = Dist.CLIENT)
public final class CarryClient {
    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        var file = Harness.CONTROL.resolve("carry-client.json");
        if (!Files.exists(file)) return;
        int id = -1;
        try {
            JsonObject command = Harness.JSON.fromJson(Files.readString(file), JsonObject.class);
            Files.delete(file);
            id = command.get("id").getAsInt();
            if (command.get("op").getAsString().equals("carry_disconnect")) {
                mc.disconnect();
                Harness.write("result-" + id + ".json", java.util.Map.of("ok", true));
                return;
            }
            if (command.get("op").getAsString().equals("carry_bind")) {
                for (String name : new String[]{"toggle", "terminal", "pluginless"}) {
                    KeyMapping mapping = java.util.Arrays.stream(mc.options.keyMappings)
                            .filter(k -> k.getName().equals("key.wflib.pda_" + name)).findFirst().orElseThrow();
                    if (command.has("require_unbound") && !mapping.isUnbound())
                        throw new IllegalStateException("Expected default unbound PDA mapping " + name);
                    mapping.setKey(InputConstants.getKey(command.get(name).getAsString()));
                }
                KeyMapping.resetMapping();
            } else if (!command.get("op").getAsString().equals("carry_assert")) throw new IllegalArgumentException("Unknown carry op");
            var values = new HashMap<String, Object>();
            values.put("ok", true);
            values.put("focused", PdaClient.focused());
            values.put("held", PdaClient.held());
            values.put("virtual", PdaClient.class.getMethod("virtualHand").invoke(null));
            values.put("carried", mc.player != null && Pda.carries(mc.player));
            values.put("physical_held", mc.player != null && Pda.held(mc.player));
            values.put("count", mc.player == null ? 0 : CarrySlots.count(mc.player));
            values.put("selected", mc.player == null ? -1 : mc.player.getInventory().selected);
            values.put("alive", mc.player != null && mc.player.isAlive());
            values.put("app", PdaClient.app() == null ? "home" : PdaClient.app().id().toString());
            values.put("keyboard_focused", com.wf.wflib.ui.input.UiKeyboard.focused() != null);
            values.put("curios_loaded", net.neoforged.fml.ModList.get().isLoaded("curios"));
            var stack = mc.player == null ? net.minecraft.world.item.ItemStack.EMPTY : CarrySlots.pda(mc.player);
            var data = stack.get(DataComponents.CUSTOM_DATA);
            values.put("token", data == null ? "" : data.copyTag().getString("carry_proof"));
            for (String key : values.keySet()) if (command.has(key)
                    && !Harness.JSON.toJsonTree(values.get(key)).equals(command.get(key)))
                throw new IllegalStateException(key + ": expected " + command.get(key) + ", got " + values.get(key));
            if (command.has("source")) {
                String source = command.get("source").getAsString();
                var actual = source.startsWith("curios:") ? CarrySlots.slot(mc.player, source.substring(7)).getStackInSlot(0)
                        : mc.player.getInventory().getItem(source.equals("offhand") ? 40 : Integer.parseInt(source));
                if (!Pda.is(actual) || actual.getCount() != 1 || !ItemStackToken.matches(actual, (String) values.get("token")))
                    throw new IllegalStateException("Client source slot lost or changed PDA");
            }
            Harness.write("result-" + id + ".json", values);
        } catch (Exception e) {
            Harness.write("result-" + id + ".json", java.util.Map.of("ok", false, "error", e.toString()));
        }
    }

    private static final class ItemStackToken {
        static boolean matches(net.minecraft.world.item.ItemStack stack, String token) {
            var data = stack.get(DataComponents.CUSTOM_DATA);
            return data != null && !token.isEmpty() && token.equals(data.copyTag().getString("carry_proof"));
        }
    }
}
