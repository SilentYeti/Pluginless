package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
//? if >=26.1 {
/*import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
*///?} else {
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
//?}

import java.util.function.Supplier;

/**
 * One named value kept in the world save. Every module stores its data through this, so the save API,
 * which changed shape after 1.21.1, is handled in one place. Call setDirty() after changing the value.
 */
// shortcut: world-save NBT files, move to SQL when a network needs to share data between servers
public final class Store<T> extends SavedData {
    public final T value;
    private final Codec<T> codec;

    private Store(T value, Codec<T> codec) {
        this.value = value;
        this.codec = codec;
    }

    //? if >=26.1 {
    /*private static final Map<String, SavedDataType<?>> TYPES = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T> Store<T> open(MinecraftServer server, String name, Codec<T> codec, Supplier<T> empty) {
        var type = (SavedDataType<Store<T>>) TYPES.computeIfAbsent(name, key -> new SavedDataType<Store<T>>(
                ResourceLocation.fromNamespaceAndPath(Pluginless.MOD_ID, key), () -> new Store<>(empty.get(), codec),
                codec.fieldOf("value").codec().xmap(value -> new Store<>(value, codec), store -> store.value)));
        return server.overworld().getDataStorage().computeIfAbsent(type);
    }
    *///?} else {
    public static <T> Store<T> open(MinecraftServer server, String name, Codec<T> codec, Supplier<T> empty) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(
                () -> new Store<>(empty.get(), codec),
                // A file that will not parse must fail loudly: starting empty would silently wipe the data.
                (tag, registries) -> new Store<>(codec.parse(NbtOps.INSTANCE, tag.get("value")).getOrThrow(), codec)),
                Pluginless.MOD_ID + "_" + name);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("value", codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow());
        return tag;
    }
    //?}
}
