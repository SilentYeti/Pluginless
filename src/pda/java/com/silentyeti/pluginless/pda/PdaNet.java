package com.silentyeti.pluginless.pda;

import com.silentyeti.pluginless.PdaHub;
import com.silentyeti.pluginless.Pluginless;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * The PDA app's packets (1.21.1 only, optional: a client without them still joins and keeps the ROTH screens).
 * Four small payloads carry every page: the client says which page is open and what it wants done, the server
 * answers with a JSON snapshot and a result line. The client never sends a price, a balance or a target state.
 */
public final class PdaNet {
    private PdaNet() {}

    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(Pluginless.MOD_ID, path); }

    /** C2S: this page is now open (a snapshot follows, and again whenever it changes). */
    public record Open(String page) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(id("pda_open"));
        public static final StreamCodec<FriendlyByteBuf, Open> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(PdaHub.MAX_ARG_LENGTH), Open::page, Open::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: the PDA was lowered or the app closed. */
    public record Close() implements CustomPacketPayload {
        public static final Type<Close> TYPE = new Type<>(id("pda_close"));
        public static final StreamCodec<FriendlyByteBuf, Close> CODEC = StreamCodec.unit(new Close());
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** C2S: one intent on a page, e.g. {@code bank.pay <player> <cents>}; {@code slot} is a picked inventory slot or -1. */
    public record Action(String page, String verb, List<String> args, int slot) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(id("pda_action"));
        public static final StreamCodec<FriendlyByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(PdaHub.MAX_ARG_LENGTH), Action::page,
                ByteBufCodecs.stringUtf8(PdaHub.MAX_ARG_LENGTH), Action::verb,
                ByteBufCodecs.stringUtf8(PdaHub.MAX_ARG_LENGTH).apply(ByteBufCodecs.list(PdaHub.MAX_ARGS)), Action::args,
                ByteBufCodecs.VAR_INT, Action::slot, Action::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: the page's snapshot as JSON (always carries balance, unread, symbol). */
    public record View(String page, String json) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(id("pda_view"));
        public static final StreamCodec<FriendlyByteBuf, View> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(PdaHub.MAX_ARG_LENGTH), View::page,
                ByteBufCodecs.stringUtf8(1 << 18), View::json, View::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** S2C: what became of the last intent; the message is a translation key or plain text. */
    public record Result(String page, boolean ok, String message) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(id("pda_result"));
        public static final StreamCodec<FriendlyByteBuf, Result> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(PdaHub.MAX_ARG_LENGTH), Result::page,
                ByteBufCodecs.BOOL, Result::ok,
                ByteBufCodecs.stringUtf8(256), Result::message, Result::new);
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            // optional(): a client that does not have this mod's channels may still join.
            var registrar = event.registrar("1").optional();
            registrar.playToServer(Open.TYPE, Open.CODEC, (payload, context) -> PdaHub.open(player(context), payload.page()));
            registrar.playToServer(Close.TYPE, Close.CODEC, (payload, context) -> PdaHub.close(player(context)));
            registrar.playToServer(Action.TYPE, Action.CODEC, (payload, context) ->
                    PdaHub.act(player(context), payload.page(), payload.verb(), payload.args(), payload.slot(), System.currentTimeMillis()));
            registrar.playToClient(View.TYPE, View.CODEC, (payload, context) -> PdaClientState.view(payload.page(), payload.json()));
            registrar.playToClient(Result.TYPE, Result.CODEC, (payload, context) -> PdaClientState.result(payload.page(), payload.ok(), payload.message()));
        });
        PdaHub.sender(new PdaHub.Sender() {
            public void view(ServerPlayer player, String page, String json) {
                if (player.connection.hasChannel(View.TYPE)) PacketDistributor.sendToPlayer(player, new View(page, json));
            }

            public void result(ServerPlayer player, String page, boolean ok, String message) {
                if (player.connection.hasChannel(Result.TYPE)) PacketDistributor.sendToPlayer(player, new Result(page, ok, message));
            }
        });
        // The app itself needs WFLib; the packets above do not, so a server without it still answers a client that has it.
        if (FMLEnvironment.dist.isClient() && ModList.get().isLoaded("wflib")) PdaClientSetup.init(modBus);
    }

    private static ServerPlayer player(IPayloadContext context) { return (ServerPlayer) context.player(); }
}
