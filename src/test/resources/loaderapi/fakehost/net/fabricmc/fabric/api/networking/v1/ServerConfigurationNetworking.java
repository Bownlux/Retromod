package net.fabricmc.fabric.api.networking.v1;

import net.fabricmc.fabric.impl.networking.FakeWire;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

public final class ServerConfigurationNetworking {
    private ServerConfigurationNetworking() {}

    public static boolean registerGlobalReceiver(CustomPacketPayload.Type<?> type, ConfigurationPacketHandler<?> handler) {
        return FakeWire.register(FakeWire.SERVER_CONFIGURATION, type, handler, true);
    }
    public static boolean registerReceiver(ServerConfigurationPacketListenerImpl listener, CustomPacketPayload.Type<?> type,
            ConfigurationPacketHandler<?> handler) {
        return FakeWire.register(FakeWire.SERVER_CONFIGURATION, type, handler, false);
    }
    public static ConfigurationPacketHandler<?> unregisterGlobalReceiver(Identifier id) {
        return (ConfigurationPacketHandler<?>) FakeWire.unregister(FakeWire.SERVER_CONFIGURATION, id, true);
    }
    public static ConfigurationPacketHandler<?> unregisterReceiver(ServerConfigurationPacketListenerImpl listener, Identifier id) {
        return (ConfigurationPacketHandler<?>) FakeWire.unregister(FakeWire.SERVER_CONFIGURATION, id, false);
    }
    public static void send(ServerConfigurationPacketListenerImpl listener, CustomPacketPayload payload) {
        FakeWire.deliver(FakeWire.SERVER_CONFIGURATION, payload, null);
    }
    public static boolean canSend(ServerConfigurationPacketListenerImpl listener, Identifier id) {
        return FakeWire.canSend(FakeWire.SERVER_CONFIGURATION, id);
    }
    public static Packet<?> createClientboundPacket(CustomPacketPayload payload) {
        return new FakeWire.PayloadPacket(FakeWire.SERVER_CONFIGURATION, payload);
    }

    /** On 26.x this name carries the payload shape; the 1.20.2 shape took a FabricPacket. */
    @FunctionalInterface
    public interface ConfigurationPacketHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        MinecraftServer server();
        ServerConfigurationPacketListenerImpl packetListener();
        PacketSender responseSender();
    }
}
