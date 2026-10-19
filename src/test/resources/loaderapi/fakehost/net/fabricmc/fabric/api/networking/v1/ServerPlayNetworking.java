package net.fabricmc.fabric.api.networking.v1;

import net.fabricmc.fabric.impl.networking.FakeWire;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public final class ServerPlayNetworking {
    private ServerPlayNetworking() {}

    public static boolean registerGlobalReceiver(CustomPacketPayload.Type<?> type, PlayPayloadHandler<?> handler) {
        return FakeWire.register(FakeWire.SERVER_PLAY, type, handler, true);
    }
    public static boolean registerReceiver(ServerGamePacketListenerImpl listener, CustomPacketPayload.Type<?> type,
            PlayPayloadHandler<?> handler) {
        return FakeWire.register(FakeWire.SERVER_PLAY, type, handler, false);
    }
    public static PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier id) {
        return (PlayPayloadHandler<?>) FakeWire.unregister(FakeWire.SERVER_PLAY, id, true);
    }
    public static PlayPayloadHandler<?> unregisterReceiver(ServerGamePacketListenerImpl listener, Identifier id) {
        return (PlayPayloadHandler<?>) FakeWire.unregister(FakeWire.SERVER_PLAY, id, false);
    }
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        FakeWire.deliver(FakeWire.SERVER_PLAY, payload, null);
    }
    public static boolean canSend(ServerPlayer player, Identifier id) { return FakeWire.canSend(FakeWire.SERVER_PLAY, id); }
    public static boolean canSend(ServerGamePacketListenerImpl listener, Identifier id) {
        return FakeWire.canSend(FakeWire.SERVER_PLAY, id);
    }
    public static Packet<?> createClientboundPacket(CustomPacketPayload payload) {
        return new FakeWire.PayloadPacket(FakeWire.SERVER_PLAY, payload);
    }
    public static PacketSender getSender(ServerPlayer player) { return FakeWire.SERVER_PLAY_SENDER; }

    @FunctionalInterface
    public interface PlayPayloadHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        MinecraftServer server();
        ServerPlayer player();
        PacketSender responseSender();
    }
}
