package com.silentyeti.pluginless;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.function.Predicate;

final class Teleports {
    static final Codec<TeleportPosition> POSITION = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("dimension").forGetter(TeleportPosition::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(TeleportPosition::x), Codec.DOUBLE.fieldOf("y").forGetter(TeleportPosition::y),
            Codec.DOUBLE.fieldOf("z").forGetter(TeleportPosition::z), Codec.FLOAT.fieldOf("yaw").forGetter(TeleportPosition::yaw),
            Codec.FLOAT.fieldOf("pitch").forGetter(TeleportPosition::pitch)
    ).apply(instance, TeleportPosition::new));
    private static final Codec<Map<String, TeleportPosition>> POINTS = Codec.unboundedMap(Codec.STRING, POSITION).xmap(HashMap::new, map -> map);
    private static final Map<MinecraftServer, Session> SESSIONS = new WeakHashMap<>();
    record Invitation(boolean here, long cost) {}
    record External(TeleportPosition origin, Predicate<ServerPlayer> arrived) {}
    record Pending(ServerPlayer player, TeleportPosition origin, Supplier<TeleportPosition> destination, Map<UUID, Long> fees, long ready) {}
    private static final class Session {
        final Requests<Invitation> requests = new Requests<>();
        final Map<UUID, Pending> warmups = new HashMap<>();
        final Map<UUID, External> external = new HashMap<>();
    }

    private static Session session(MinecraftServer server) { return SESSIONS.computeIfAbsent(server, key -> new Session()); }
    static Requests<Invitation> requests(MinecraftServer server) { return session(server).requests; }
    static Store<Map<String, TeleportPosition>> destinations(MinecraftServer server) {
        return Store.open(server, "destinations", POINTS, HashMap::new);
    }
    static Store<Map<UUID, Map<String, TeleportPosition>>> homes(MinecraftServer server) {
        return Store.open(server, "homes", Codec.unboundedMap(UUIDUtil.STRING_CODEC, POINTS).xmap(HashMap::new, map -> map), HashMap::new);
    }
    private static Store<Map<UUID, TeleportPosition>> backs(MinecraftServer server) {
        return Store.open(server, "back", Codec.unboundedMap(UUIDUtil.STRING_CODEC, POSITION).xmap(HashMap::new, map -> map), HashMap::new);
    }
    static Map<String, TeleportPosition> homesOf(MinecraftServer server, UUID player) { return homes(server).value.getOrDefault(player, Map.of()); }
    static List<String> warps(MinecraftServer server) { return destinations(server).value.keySet().stream().filter(key -> key.startsWith("warp.")).map(key -> key.substring(5)).sorted().toList(); }
    private static String id(net.minecraft.resources.ResourceKey<?> key) {
        //? if >=26.1 {
        /*return key.identifier().toString();
        *///?} else {
        return key.location().toString();
        //?}
    }
    static TeleportPosition position(ServerPlayer player) {
        return new TeleportPosition(id(player.level().dimension()), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    static TeleportPosition spawn(MinecraftServer server) {
        var configured = destinations(server).value.get("spawn");
        if (configured != null) return configured;
        //? if >=26.1 {
        /*var spawn = server.getRespawnData();
        var pos = spawn.pos();
        return new TeleportPosition(id(spawn.dimension()), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, spawn.yaw(), spawn.pitch());
        *///?} else {
        var level = server.overworld();
        var pos = level.getSharedSpawnPos();
        return new TeleportPosition(id(level.dimension()), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.getSharedSpawnAngle(), 0);
        //?}
    }

    static boolean fee(ServerPlayer player, String command) {
        var server = player.level().getServer();
        long cost = Features.cost(command);
        return server != null && (cost == 0 || Ledger.collect(server, player.getUUID(), cost, "command." + command));
    }

    static boolean setSpawn(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null || !fee(player, "setspawn")) return false;
        var store = destinations(server);
        store.value.put("spawn", position(player)); store.setDirty();
        return true;
    }

    static boolean setHome(ServerPlayer player, String name) {
        var server = player.level().getServer();
        if (server == null || !FeatureSettings.key(name)) return false;
        var current = homesOf(server, player.getUUID());
        double limit = Math.max(0, RankGate.ranks.number(RankGate.rankOf(server, player.getUUID()), "homes", 1));
        if (!current.containsKey(name) && current.size() >= Math.floor(limit) || !fee(player, "sethome")) return false;
        var store = homes(server);
        store.value.computeIfAbsent(player.getUUID(), key -> new HashMap<>()).put(name, position(player)); store.setDirty();
        return true;
    }

    static boolean delHome(ServerPlayer player, String name) {
        var server = player.level().getServer();
        if (server == null || !homesOf(server, player.getUUID()).containsKey(name) || !fee(player, "delhome")) return false;
        var store = homes(server); store.value.get(player.getUUID()).remove(name); store.setDirty();
        return true;
    }

    static boolean setWarp(ServerPlayer player, String name) {
        var server = player.level().getServer();
        if (server == null || !FeatureSettings.key(name) || !fee(player, "setwarp")) return false;
        var store = destinations(server); store.value.put("warp." + name, position(player)); store.setDirty();
        return true;
    }

    static boolean delWarp(ServerPlayer player, String name) {
        var server = player.level().getServer();
        if (server == null || !destinations(server).value.containsKey("warp." + name) || !fee(player, "delwarp")) return false;
        var store = destinations(server); store.value.remove("warp." + name); store.setDirty();
        return true;
    }

    static boolean go(ServerPlayer player, String command, TeleportPosition destination) {
        return start(player, () -> destination, Map.of(player.getUUID(), Features.cost(command)), System.currentTimeMillis());
    }

    static boolean back(ServerPlayer player) {
        var server = player.level().getServer();
        return server != null && go(player, "back", backs(server).value.get(player.getUUID()));
    }

    static boolean start(ServerPlayer player, Supplier<TeleportPosition> destination, Map<UUID, Long> fees, long now) {
        var server = player.level().getServer();
        if (server == null || !player.isAlive() || destination.get() == null) return false;
        int seconds = RankGate.ranks.flag(RankGate.rankOf(server, player.getUUID()), "teleport_no_warmup") ? 0 : Config.teleportWarmupSeconds();
        var pending = new Pending(player, position(player), destination, fees, now + seconds * 1000L);
        if (seconds == 0) return finish(server, pending);
        if (session(server).warmups.putIfAbsent(player.getUUID(), pending) != null) return false;
        player.sendSystemMessage(Component.translatable("pluginless.teleport.warmup", seconds));
        return true;
    }

    static void tick(MinecraftServer server, long now) {
        var session = session(server);
        for (var entry : List.copyOf(session.external.entrySet())) {
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && entry.getValue().origin().moved(position(player)) && entry.getValue().arrived().test(player)) remember(server, entry.getKey(), entry.getValue().origin());
            session.external.remove(entry.getKey());
        }
        for (var pending : List.copyOf(session.warmups.values())) {
            var player = pending.player();
            if (!player.isAlive() || pending.origin().moved(position(player))) { cancel(player); continue; }
            if (now < pending.ready()) continue;
            session.warmups.remove(player.getUUID());
            if (!finish(server, pending)) player.sendSystemMessage(Component.translatable("pluginless.teleport.failed"));
        }
    }

    static void cancel(ServerPlayer player) {
        var server = player.level().getServer();
        if (server != null && session(server).warmups.remove(player.getUUID()) != null) player.sendSystemMessage(Component.translatable("pluginless.teleport.cancelled"));
    }

    private static boolean finish(MinecraftServer server, Pending pending) {
        var point = pending.destination().get();
        var player = pending.player();
        if (point == null || !player.isAlive() || !safe(server, player, point)) return false;
        var accounts = Ledger.of(server);
        long total = 0;
        for (var fee : pending.fees().entrySet()) {
            if (fee.getValue() < 0 || accounts.balance(fee.getKey()) < fee.getValue()) return false;
            total = Math.addExact(total, fee.getValue());
        }
        if (Long.MAX_VALUE - accounts.balance(Accounts.SERVER) < total) return false;
        for (var fee : pending.fees().entrySet()) {
            if (fee.getValue() > 0 && !Ledger.collect(server, fee.getKey(), fee.getValue(), "teleport.fee")) throw new IllegalStateException("teleport fee preflight failed");
        }
        var origin = position(player);
        var level = level(server, point);
        player.stopRiding();
        //? if >=26.1 {
        /*boolean success = player.teleportTo(level, point.x(), point.y(), point.z(), Set.of(), point.yaw(), point.pitch(), true);
        *///?} else {
        boolean success = player.teleportTo(level, point.x(), point.y(), point.z(), Set.of(), point.yaw(), point.pitch());
        //?}
        if (!success) {
            for (var fee : pending.fees().entrySet()) {
                if (fee.getValue() > 0 && !Ledger.of(server, "server", "teleport.refund").refund(fee.getKey(), fee.getValue())) throw new IllegalStateException("teleport refund failed");
            }
            return false;
        }
        session(server).external.remove(player.getUUID());
        remember(server, player.getUUID(), origin);
        player.setDeltaMovement(Vec3.ZERO); player.resetFallDistance();
        player.sendSystemMessage(Component.translatable("pluginless.teleport.done"));
        return true;
    }

    private static ServerLevel level(MinecraftServer server, TeleportPosition point) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(point.dimension())));
    }

    static boolean safe(MinecraftServer server, ServerPlayer player, TeleportPosition point) {
        var level = level(server, point);
        if (level == null) return false;
        var feet = BlockPos.containing(point.x(), point.y(), point.z());
        var box = new AABB(point.x() - 0.3, point.y(), point.z() - 0.3, point.x() + 0.3, point.y() + 1.8, point.z() + 0.3);
        if (level.isOutsideBuildHeight(feet.below()) || level.isOutsideBuildHeight(feet.above(2))
                || !level.getWorldBorder().isWithinBounds(box) || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                || !level.noCollision(player, box)) return false;
        // shortcut: checks vanilla hazards, add mod-specific checks before allowing dimensions with custom hazards
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = -1; y <= 2; y++) {
            var state = level.getBlockState(feet.offset(x, y, z));
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW)
                    || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE)) return false;
        }
        return true;
    }

    static boolean rtp(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null || !Features.settings.rtpDimensions.contains(id(player.level().dimension()))) return false;
        var level = (ServerLevel) player.level();
        int radius = Config.rtpRadius();
        // shortcut: bounded synchronous chunk loads, use asynchronous search if configured radii cause tick stalls
        for (int attempt = 0; attempt < Config.rtpAttempts(); attempt++) {
            int x = ThreadLocalRandom.current().nextInt(-radius, radius + 1), z = ThreadLocalRandom.current().nextInt(-radius, radius + 1);
            if ((x + 0.5) * (x + 0.5) + (z + 0.5) * (z + 0.5) > (long) radius * radius || !level.getWorldBorder().isWithinBounds(new BlockPos(x, 0, z))) continue;
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            var point = new TeleportPosition(id(level.dimension()), x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
            if (safe(server, player, point)) return go(player, "rtp", point);
        }
        return false;
    }

    static boolean request(ServerPlayer sender, ServerPlayer recipient, boolean here) {
        var server = sender.level().getServer();
        if (server == null || !requests(server).put(sender.getUUID(), recipient.getUUID(), new Invitation(here, Features.cost(here ? "tpahere" : "tpa")), Config.teleportRequestMillis(), System.currentTimeMillis())) return false;
        recipient.sendSystemMessage(Component.translatable(here ? "pluginless.teleport.request_here" : "pluginless.teleport.request", sender.getScoreboardName()));
        return true;
    }

    static boolean respond(ServerPlayer recipient, boolean accept) {
        var server = recipient.level().getServer();
        if (server == null) return false;
        var request = requests(server).get(recipient.getUUID(), System.currentTimeMillis());
        if (request == null) return false;
        var sender = server.getPlayerList().getPlayer(request.sender());
        if (!accept) {
            if (!fee(recipient, "tpdeny")) return false;
            requests(server).take(recipient.getUUID(), System.currentTimeMillis());
            if (sender != null) sender.sendSystemMessage(Component.translatable("pluginless.teleport.denied", recipient.getScoreboardName()));
            return true;
        }
        if (sender == null) return false;
        var moving = request.value().here() ? recipient : sender;
        var target = request.value().here() ? sender : recipient;
        var fees = Map.of(sender.getUUID(), request.value().cost(), recipient.getUUID(), Features.cost("tpaccept"));
        if (!start(moving, () -> server.getPlayerList().getPlayer(target.getUUID()) == target && target.isAlive() ? position(target) : null, fees, System.currentTimeMillis())) return false;
        requests(server).take(recipient.getUUID(), System.currentTimeMillis());
        return true;
    }

    static Component pending(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null) return Component.empty();
        var request = requests(server).get(player.getUUID(), System.currentTimeMillis());
        return request == null ? Component.translatable("pluginless.teleport.no_request")
                : Component.translatable(request.value().here() ? "pluginless.teleport.request_here" : "pluginless.teleport.request", Commands.name(server, request.sender()));
    }

    static void remember(ServerPlayer player) {
        var server = player.level().getServer();
        if (server != null) session(server).external.remove(player.getUUID());
        remember(server, player.getUUID(), position(player));
    }
    private static void remember(MinecraftServer server, UUID player, TeleportPosition point) {
        if (server == null) return;
        var store = backs(server); store.value.put(player, point); store.setDirty();
    }
    static void external(ServerPlayer player, Predicate<ServerPlayer> arrived) {
        var server = player.level().getServer();
        if (server != null) session(server).external.put(player.getUUID(), new External(position(player), arrived));
    }
    static void leave(ServerPlayer player) {
        var server = player.level().getServer();
        if (server == null) return;
        session(server).warmups.remove(player.getUUID()); session(server).external.remove(player.getUUID()); requests(server).remove(player.getUUID());
    }
    static void clear(MinecraftServer server) { SESSIONS.remove(server); }
}
