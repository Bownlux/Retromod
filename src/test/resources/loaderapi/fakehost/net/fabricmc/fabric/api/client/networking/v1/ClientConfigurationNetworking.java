package net.fabricmc.fabric.api.client.networking.v1;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.impl.networking.FakeWire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class ClientConfigurationNetworking {
    private ClientConfigurationNetworking() {}

    public static boolean registerGlobalReceiver(CustomPacketPayload.Type<?> type, ConfigurationPayloadHandler<?> handler) {
        return FakeWire.register(FakeWire.CLIENT_CONFIGURATION, type, handler, true);
    }
    public static boolean registerReceiver(CustomPacketPayload.Type<?> type, ConfigurationPayloadHandler<?> handler) {
        return FakeWire.register(FakeWire.CLIENT_CONFIGURATION, type, handler, false);
    }
    public static ConfigurationPayloadHandler<?> unregisterGlobalReceiver(CustomPacketPayload.Type<?> type) {
        return (ConfigurationPayloadHandler<?>) FakeWire.unregister(FakeWire.CLIENT_CONFIGURATION, type.id(), true);
    }
    public static ConfigurationPayloadHandler<?> unregisterReceiver(Identifier id) {
        return (ConfigurationPayloadHandler<?>) FakeWire.unregister(FakeWire.CLIENT_CONFIGURATION, id, false);
    }
    public static void send(CustomPacketPayload payload) { FakeWire.deliver(FakeWire.CLIENT_CONFIGURATION, payload, null); }
    public static boolean canSend(Identifier id) { return FakeWire.canSend(FakeWire.CLIENT_CONFIGURATION, id); }

    @FunctionalInterface
    public interface ConfigurationPayloadHandler<T extends CustomPacketPayload> {
        void receive(T payload, Context context);
    }

    public interface Context {
        Minecraft client();
        ClientConfigurationPacketListenerImpl packetListener();
        PacketSender responseSender();
    }
}
