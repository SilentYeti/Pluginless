package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
//? if >=26.1 {
/*import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedDataType;
*///?} else {
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
//?}

import java.util.Map;
import java.util.UUID;

// shortcut: balances live in one world-save NBT file, move to SQL when a network needs to share them
public final class Ledger extends SavedData {
    private static final Codec<Map<UUID, Long>> BALANCES = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG);

    public final Accounts accounts = new Accounts(Config.startBalanceCents(), this::setDirty);

    private Ledger() {
    }

    private Ledger(Map<UUID, Long> balances) {
        accounts.balances.putAll(balances);
    }

    //? if >=26.1 {
    /*private static final SavedDataType<Ledger> TYPE = new SavedDataType<>(
            ResourceLocation.fromNamespaceAndPath(Pluginless.MOD_ID, "ledger"), Ledger::new,
            BALANCES.fieldOf("balances").codec().xmap(Ledger::new, ledger -> ledger.accounts.balances));

    public static Accounts of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE).accounts;
    }
    *///?} else {
    public static Accounts of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new Factory<>(Ledger::new, Ledger::load), "pluginless_ledger").accounts;
    }

    // A file that will not parse must fail loudly: starting empty would silently wipe every balance.
    private static Ledger load(CompoundTag tag, HolderLookup.Provider registries) {
        return new Ledger(BALANCES.parse(NbtOps.INSTANCE, tag.get("balances")).getOrThrow());
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("balances", BALANCES.encodeStart(NbtOps.INSTANCE, accounts.balances).getOrThrow());
        return tag;
    }
    //?}
}
