package net.fabricmc.fabric.api.client.networking.v1;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.impl.networking.FakeWire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class ClientPlayNetworking {
    private ClientPlayNetworking() {}

    public static boolean registerGlobalReceiver(CustomPacketPayload.Type<?> type, PlayPayloadHandler<?> handler) {
        return FakeWire.register(FakeWire.CLIENT_PLAY, type, handler, true);
    }
    public static boolean registerReceiver(CustomPacketPayload.Type<?> type, PlayPayloadHandler<?> handler) {
        return FakeWire.register(FakeWire.CLIENT_PLAY, type, handler, false);
    }
    public static PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier id) {
        return (PlayPayloadHandler<?>) FakeWire.unregister(FakeWire.CLIENT_PLAY, id, true);
    }
    public static PlayPayloadHandler<?> unregisterReceiver(Identifier id) {
        return (PlayPayloadHandler<?>) FakeWire.unregister(FakeWire.CLIENT_PLAY, id, false);
    }
    public static void send(CustomPacketPayload payload) { FakeWire.deliver(FakeWire.CLIENT_PLAY, payload, null); }
    public static boolean canSend(Identifier id) { return FakeWire.canSend(FakeWire.CLIENT_PLAY, id); }
    public static boolean canSend(CustomPacketPayload.Type<?> type) { return FakeWire.canSend(FakeWire.CLIENT_PLAY, type.id()); }
    public static Packet<?> createServerboundPacket(CustomPacketPayload payload) {
        return new FakeWire.PayloadPacket(FakeWire.CLIENT_PLAY, payload);
    }
    public static PacketSender getSender() { return FakeWire.CLIENT_PLAY_SENDER; }

    @FunctionalInterface
    public interface PlayPayloadHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        Minecraft client();
        LocalPlayer player();
        PacketSender responseSender();
    }
}
