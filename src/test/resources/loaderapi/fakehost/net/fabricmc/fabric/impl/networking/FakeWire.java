package net.fabricmc.fabric.impl.networking;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One server and one client in one JVM, joined by a fake connection. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class FakeWire {
    public static final String SERVER_PLAY = "server-play";
    public static final String CLIENT_PLAY = "client-play";
    public static final String SERVER_CONFIGURATION = "server-configuration";
    public static final String CLIENT_CONFIGURATION = "client-configuration";

    public static final MinecraftServer SERVER = new MinecraftServer();
    public static final ServerPlayer PLAYER = new ServerPlayer(new ServerLevel(SERVER));
    public static final ServerGamePacketListenerImpl CONNECTION = new ServerGamePacketListenerImpl(PLAYER);
    public static final ServerConfigurationPacketListenerImpl SERVER_CONFIG_LISTENER = new ServerConfigurationPacketListenerImpl();
    public static final ClientConfigurationPacketListenerImpl CLIENT_CONFIG_LISTENER = new ClientConfigurationPacketListenerImpl();
    public static final PacketSender SERVER_PLAY_SENDER = new ServerPlayNetworkAddon();
    public static final PacketSender CLIENT_PLAY_SENDER = new ClientPlayNetworkAddon();
    public static final PacketSender SERVER_CONFIG_SENDER = new ServerConfigurationNetworkAddon();
    public static final PacketSender CLIENT_CONFIG_SENDER = new ClientConfigurationNetworkAddon();
    public static final PacketSender LOGIN_SENDER = new ServerLoginNetworkAddon();

    /** What crossed the wire, one line per event, for the tests to read. */
    public static final List<String> LOG = new ArrayList<>();

    private static final Map<String, Map<Identifier, Object>> GLOBAL = new HashMap<>();
    private static final Map<String, Map<Identifier, Object>> CONNECTIONS = new HashMap<>();

    private FakeWire() {}

    public static void reset() {
        GLOBAL.clear();
        CONNECTIONS.clear();
        LOG.clear();
        PayloadTypeRegistryImpl.resetAll();
    }

    /** Registers a receiver, refusing a channel with no payload type as Fabric API 26.3 does. */
    public static boolean register(String endpoint, CustomPacketPayload.Type<?> type, Object handler, boolean global) {
        if (receiveRegistry(endpoint).get(type.id()) == null) {
            throw new IllegalArgumentException("Cannot register handler as no payload type has been registered with name \""
                    + type.id() + "\" for " + endpoint);
        }
        Map<Identifier, Object> receivers = (global ? GLOBAL : CONNECTIONS).computeIfAbsent(endpoint, k -> new HashMap<>());
        return receivers.putIfAbsent(type.id(), handler) == null;
    }

    public static Object unregister(String endpoint, Identifier id, boolean global) {
        return (global ? GLOBAL : CONNECTIONS).computeIfAbsent(endpoint, k -> new HashMap<>()).remove(id);
    }

    /** Whether the other side has a receiver for {@code id}. */
    public static boolean canSend(String from, Identifier id) {
        return receiver(counterpart(from), id) != null;
    }

    // A sent payload is encoded with the sending registry's codec, decoded with it again, and
    // handed to the other side's receiver, as after the bytes crossed the wire.
    public static void deliver(String from, CustomPacketPayload payload, ChannelFutureListener listener) {
        String to = counterpart(from);
        Identifier id = payload.type().id();
        CustomPacketPayload.TypeAndCodec entry = sendRegistry(from).get(id);
        // Minecraft writes a payload no registry knows as its id alone, and the other side ignores it.
        if (entry == null && payload instanceof DiscardedPayload) {
            LOG.add("discarded " + to + " " + id);
            return;
        }
        if (entry == null) throw new IllegalStateException("No payload type registered for " + id + " towards " + to);
        ByteBuf wire = Unpooled.buffer();
        entry.codec().encode(wire, payload);
        int size = wire.readableBytes();
        Object decoded = entry.codec().decode(wire);
        if (wire.readableBytes() != 0) throw new IllegalStateException("The codec for " + id + " left bytes unread");
        Object handler = receiver(to, id);
        LOG.add((handler == null ? "dropped " : "delivered ") + to + " " + id + " " + size);
        if (handler != null) dispatch(to, handler, decoded);
        if (listener != null) {
            try {
                listener.operationComplete(new Done());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void dispatch(String to, Object handler, Object payload) {
        switch (to) {
            case SERVER_PLAY -> ((ServerPlayNetworking.PlayPayloadHandler) handler).receive((CustomPacketPayload) payload,
                    new ServerPlayNetworking.Context() {
                        public MinecraftServer server() { return SERVER; }
                        public ServerPlayer player() { return PLAYER; }
                        public PacketSender responseSender() { return SERVER_PLAY_SENDER; }
                    });
            case CLIENT_PLAY -> ((ClientPlayNetworking.PlayPayloadHandler) handler).receive((CustomPacketPayload) payload,
                    new ClientPlayNetworking.Context() {
                        public Minecraft client() { return Minecraft.getInstance(); }
                        public LocalPlayer player() { return Minecraft.getInstance().player; }
                        public PacketSender responseSender() { return CLIENT_PLAY_SENDER; }
                    });
            case SERVER_CONFIGURATION -> ((ServerConfigurationNetworking.ConfigurationPacketHandler) handler).receive(
                    (CustomPacketPayload) payload, new ServerConfigurationNetworking.Context() {
                        public MinecraftServer server() { return SERVER; }
                        public ServerConfigurationPacketListenerImpl packetListener() { return SERVER_CONFIG_LISTENER; }
                        public PacketSender responseSender() { return SERVER_CONFIG_SENDER; }
                    });
            default -> ((ClientConfigurationNetworking.ConfigurationPayloadHandler) handler).receive(
                    (CustomPacketPayload) payload, new ClientConfigurationNetworking.Context() {
                        public Minecraft client() { return Minecraft.getInstance(); }
                        public ClientConfigurationPacketListenerImpl packetListener() { return CLIENT_CONFIG_LISTENER; }
                        public PacketSender responseSender() { return CLIENT_CONFIG_SENDER; }
                    });
        }
    }

    private static Object receiver(String endpoint, Identifier id) {
        Object connection = CONNECTIONS.getOrDefault(endpoint, Map.of()).get(id);
        return connection != null ? connection : GLOBAL.getOrDefault(endpoint, Map.of()).get(id);
    }

    static String counterpart(String endpoint) {
        return switch (endpoint) {
            case SERVER_PLAY -> CLIENT_PLAY;
            case CLIENT_PLAY -> SERVER_PLAY;
            case SERVER_CONFIGURATION -> CLIENT_CONFIGURATION;
            default -> SERVER_CONFIGURATION;
        };
    }

    private static PayloadTypeRegistryImpl receiveRegistry(String endpoint) {
        return sendRegistry(counterpart(endpoint));
    }

    private static PayloadTypeRegistryImpl sendRegistry(String endpoint) {
        return switch (endpoint) {
            case SERVER_PLAY -> PayloadTypeRegistryImpl.CLIENTBOUND_PLAY;
            case CLIENT_PLAY -> PayloadTypeRegistryImpl.SERVERBOUND_PLAY;
            case SERVER_CONFIGURATION -> PayloadTypeRegistryImpl.CLIENTBOUND_CONFIGURATION;
            default -> PayloadTypeRegistryImpl.SERVERBOUND_CONFIGURATION;
        };
    }

    /** A packet that carries one payload from one side. */
    public record PayloadPacket(String from, CustomPacketPayload payload) implements Packet<Object> {}

    /** A login query packet: a raw channel and its bytes. */
    public record LoginPacket(Identifier channel, int size) implements Packet<Object> {}

    private static final class Done implements ChannelFuture {
        public boolean isSuccess() { return true; }
    }

    /** The sender each side's connection has, named like Fabric API's own addons. */
    abstract static class Addon implements PacketSender {
        private final String endpoint;
        Addon(String endpoint) { this.endpoint = endpoint; }
        public Packet<?> createPacket(CustomPacketPayload payload) { return new PayloadPacket(endpoint, payload); }
        public void sendPacket(Packet<?> packet, ChannelFutureListener listener) {
            if (packet instanceof PayloadPacket payloadPacket) {
                deliver(payloadPacket.from(), payloadPacket.payload(), listener);
            } else if (packet instanceof LoginPacket login) {
                LOG.add("login " + login.channel() + " " + login.size());
            }
        }
    }

    static final class ServerPlayNetworkAddon extends Addon { ServerPlayNetworkAddon() { super(SERVER_PLAY); } }
    static final class ClientPlayNetworkAddon extends Addon { ClientPlayNetworkAddon() { super(CLIENT_PLAY); } }
    static final class ServerConfigurationNetworkAddon extends Addon { ServerConfigurationNetworkAddon() { super(SERVER_CONFIGURATION); } }
    static final class ClientConfigurationNetworkAddon extends Addon { ClientConfigurationNetworkAddon() { super(CLIENT_CONFIGURATION); } }
}
