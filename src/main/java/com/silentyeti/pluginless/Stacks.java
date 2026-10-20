package com.silentyeti.pluginless;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Items held outside an inventory are kept as JSON text with every data component, the same on all versions. */
final class Stacks {
    static String write(MinecraftServer server, ItemStack stack) {
        return ItemStack.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow().toString();
    }

    static ItemStack read(MinecraftServer server, String json) {
        return ItemStack.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
    }

    static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** What a shulker box or bundle holds, read from the stored text: "16 minecraft:bread". */
    static List<String> contents(String json) {
        var lines = new ArrayList<String>();
        var components = JsonParser.parseString(json).getAsJsonObject().get("components");
        if (!(components instanceof JsonObject object)) return lines;
        for (String key : new String[]{"minecraft:container", "minecraft:bundle_contents"}) {
            if (!(object.get(key) instanceof com.google.gson.JsonArray entries)) continue;
            for (JsonElement entry : entries) {
                var item = entry.getAsJsonObject();
                if (item.has("item")) item = item.getAsJsonObject("item");
                lines.add((item.has("count") ? item.get("count").getAsInt() : 1) + " " + item.get("id").getAsString());
            }
        }
        return lines;
    }
}
